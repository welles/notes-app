using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;

namespace Notes.Server.Security;

public sealed class CloudflareAccessOptions
{
    public const string Section = "CloudflareAccess";

    public bool Enabled { get; set; } = true;

    /// <summary>e.g. <c>myteam.cloudflareaccess.com</c></summary>
    public string? TeamDomain { get; set; }

    /// <summary>AUD tag of the Access application.</summary>
    public string? Audience { get; set; }

    public string Issuer => "https://" + TeamDomain!.Trim().TrimEnd('/').Replace("https://", "");
    public string CertsUrl => Issuer + "/cdn-cgi/access/certs";
}

/// <summary>Provides the public keys Cloudflare Access signs its JWTs with.</summary>
public interface ICloudflareKeyProvider
{
    Task<IReadOnlyList<SecurityKey>> GetKeysAsync(bool forceRefresh, CancellationToken ct);
}

/// <summary>Fetches and caches the JWKS from <c>https://&lt;team&gt;/cdn-cgi/access/certs</c> (keys rotate, so refresh on unknown kid).</summary>
public sealed class CloudflareKeyProvider(HttpClient http, IOptions<CloudflareAccessOptions> options, TimeProvider time) : ICloudflareKeyProvider
{
    private static readonly TimeSpan CacheFor = TimeSpan.FromHours(1);
    private static readonly TimeSpan MinRefreshInterval = TimeSpan.FromMinutes(1);
    private readonly SemaphoreSlim _lock = new(1, 1);
    private IReadOnlyList<SecurityKey> _keys = [];
    private DateTimeOffset _fetchedAt = DateTimeOffset.MinValue;

    public async Task<IReadOnlyList<SecurityKey>> GetKeysAsync(bool forceRefresh, CancellationToken ct)
    {
        var age = time.GetUtcNow() - _fetchedAt;
        if (_keys.Count > 0 && age < CacheFor && !(forceRefresh && age > MinRefreshInterval)) return _keys;

        await _lock.WaitAsync(ct);
        try
        {
            age = time.GetUtcNow() - _fetchedAt;
            if (_keys.Count > 0 && age < CacheFor && !(forceRefresh && age > MinRefreshInterval)) return _keys;
            var json = await http.GetStringAsync(options.Value.CertsUrl, ct);
            _keys = new JsonWebKeySet(json).GetSigningKeys().ToList();
            _fetchedAt = time.GetUtcNow();
            return _keys;
        }
        finally
        {
            _lock.Release();
        }
    }
}

/// <summary>
/// Defense in depth: every request (except <c>/healthz</c>) must carry a valid <c>Cf-Access-Jwt-Assertion</c>
/// issued by our Cloudflare Access team for our application. Otherwise 403.
/// </summary>
public sealed class CloudflareAccessMiddleware(
    RequestDelegate next,
    IOptions<CloudflareAccessOptions> options,
    ICloudflareKeyProvider keys,
    ILogger<CloudflareAccessMiddleware> log)
{
    public const string Header = "Cf-Access-Jwt-Assertion";
    private readonly JsonWebTokenHandler _handler = new();

    public async Task InvokeAsync(HttpContext http)
    {
        if (!options.Value.Enabled || http.Request.Path.Equals("/healthz", StringComparison.OrdinalIgnoreCase))
        {
            await next(http);
            return;
        }

        var token = http.Request.Headers[Header].ToString();
        if (string.IsNullOrEmpty(token) || !await ValidateAsync(token, http.RequestAborted))
        {
            http.Response.StatusCode = StatusCodes.Status403Forbidden;
            await http.Response.WriteAsync("Forbidden");
            return;
        }
        await next(http);
    }

    private async Task<bool> ValidateAsync(string token, CancellationToken ct)
    {
        var result = await ValidateWithKeysAsync(token, await keys.GetKeysAsync(false, ct));
        if (result.Exception is SecurityTokenSignatureKeyNotFoundException)
            result = await ValidateWithKeysAsync(token, await keys.GetKeysAsync(true, ct));
        if (!result.IsValid) log.LogWarning("Rejected Cloudflare Access JWT: {Reason}", result.Exception?.GetType().Name);
        return result.IsValid;
    }

    private Task<TokenValidationResult> ValidateWithKeysAsync(string token, IReadOnlyList<SecurityKey> signingKeys) =>
        _handler.ValidateTokenAsync(token, new TokenValidationParameters
        {
            ValidIssuer = options.Value.Issuer,
            ValidAudience = options.Value.Audience,
            IssuerSigningKeys = signingKeys,
            ValidateIssuerSigningKey = true,
            RequireSignedTokens = true,
            RequireExpirationTime = true,
            ClockSkew = TimeSpan.FromMinutes(1),
        });
}

public static class CloudflareAccessExtensions
{
    public static IServiceCollection AddCloudflareAccess(this IServiceCollection services, IConfiguration config)
    {
        services.Configure<CloudflareAccessOptions>(config.GetSection(CloudflareAccessOptions.Section));
        services.AddHttpClient(nameof(CloudflareKeyProvider));
        services.AddSingleton<ICloudflareKeyProvider>(sp => new CloudflareKeyProvider(
            sp.GetRequiredService<IHttpClientFactory>().CreateClient(nameof(CloudflareKeyProvider)),
            sp.GetRequiredService<IOptions<CloudflareAccessOptions>>(),
            sp.GetRequiredService<TimeProvider>()));
        return services;
    }

    /// <summary>Fails fast on incomplete configuration and warns loudly when the check is disabled.</summary>
    public static void ValidateCloudflareAccess(this WebApplication app)
    {
        var o = app.Services.GetRequiredService<IOptions<CloudflareAccessOptions>>().Value;
        if (!o.Enabled)
        {
            app.Logger.LogWarning("!!! Cloudflare Access JWT validation is DISABLED (CloudflareAccess__Enabled=false). Only use this for local development. !!!");
            return;
        }
        if (string.IsNullOrWhiteSpace(o.TeamDomain) || string.IsNullOrWhiteSpace(o.Audience))
            throw new InvalidOperationException("CloudflareAccess__TeamDomain and CloudflareAccess__Audience must be set (or CloudflareAccess__Enabled=false for local development).");
    }
}
