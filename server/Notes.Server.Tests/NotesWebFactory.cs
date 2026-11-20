using System.Security.Cryptography;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;
using Notes.Server.Security;

namespace Notes.Server.Tests;

/// <summary>The real server over HTTP, on a temporary SQLite file.</summary>
public sealed class NotesWebFactory(bool cloudflareAccess = false) : WebApplicationFactory<Program>
{
    public const string TeamDomain = "notes-test.cloudflareaccess.com";
    public const string Audience = "test-aud-tag";

    private readonly string _dbPath = Path.Combine(Path.GetTempPath(), $"notes-test-{Guid.NewGuid():N}.db");

    public static RsaSecurityKey SigningKey { get; } = new(RSA.Create(2048)) { KeyId = "test-key" };

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");
        builder.UseSetting("ConnectionStrings:Default", $"Data Source={_dbPath}");
        builder.UseSetting("CloudflareAccess:Enabled", cloudflareAccess ? "true" : "false");
        builder.UseSetting("CloudflareAccess:TeamDomain", TeamDomain);
        builder.UseSetting("CloudflareAccess:Audience", Audience);
        builder.UseSetting("Notes:MinClientVersion:android", "1.2.0");
        builder.UseSetting("Notes:DataProtectionKeysPath", Path.Combine(Path.GetTempPath(), "notes-test-keys"));
        builder.ConfigureServices(s => s.Replace(ServiceDescriptor.Singleton<ICloudflareKeyProvider>(new StaticKeyProvider(SigningKey))));
    }

    public static string CreateJwt(
        string audience = Audience, string issuer = "https://" + TeamDomain, DateTime? expires = null, SecurityKey? key = null)
    {
        var now = DateTime.UtcNow;
        var exp = expires ?? now.AddHours(1);
        return new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
        {
            Issuer = issuer,
            Audience = audience,
            IssuedAt = exp.AddHours(-2),
            NotBefore = exp.AddHours(-2),
            Expires = exp,
            Claims = new Dictionary<string, object> { ["email"] = "me@example.com" },
            SigningCredentials = new SigningCredentials(key ?? SigningKey, SecurityAlgorithms.RsaSha256),
        });
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        foreach (var f in new[] { _dbPath, _dbPath + "-wal", _dbPath + "-shm" })
            try { File.Delete(f); } catch { /* best effort */ }
    }

    private sealed class StaticKeyProvider(SecurityKey key) : ICloudflareKeyProvider
    {
        public Task<IReadOnlyList<SecurityKey>> GetKeysAsync(bool forceRefresh, CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<SecurityKey>>([key]);
    }
}
