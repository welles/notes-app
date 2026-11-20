using System.Net;
using System.Security.Cryptography;
using Microsoft.IdentityModel.Tokens;
using Notes.Server.Security;

namespace Notes.Server.Tests;

public sealed class CloudflareAccessTests : IAsyncLifetime
{
    private readonly NotesWebFactory _factory = new(cloudflareAccess: true);
    private HttpClient _http = null!;

    public ValueTask InitializeAsync()
    {
        _http = _factory.CreateClient();
        return ValueTask.CompletedTask;
    }

    public async ValueTask DisposeAsync() => await _factory.DisposeAsync();

    private async Task<HttpStatusCode> GetWithAsync(string? jwt, string path = "/api/meta")
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, path);
        if (jwt is not null) request.Headers.Add(CloudflareAccessMiddleware.Header, jwt);
        return (await _http.SendAsync(request)).StatusCode;
    }

    [Fact]
    public async Task Valid_token_is_accepted() =>
        Assert.Equal(HttpStatusCode.OK, await GetWithAsync(NotesWebFactory.CreateJwt()));

    [Fact]
    public async Task Missing_token_is_forbidden() =>
        Assert.Equal(HttpStatusCode.Forbidden, await GetWithAsync(null));

    [Fact]
    public async Task Wrong_audience_is_forbidden() =>
        Assert.Equal(HttpStatusCode.Forbidden, await GetWithAsync(NotesWebFactory.CreateJwt(audience: "other-app")));

    [Fact]
    public async Task Wrong_issuer_is_forbidden() =>
        Assert.Equal(HttpStatusCode.Forbidden, await GetWithAsync(NotesWebFactory.CreateJwt(issuer: "https://evil.cloudflareaccess.com")));

    [Fact]
    public async Task Expired_token_is_forbidden() =>
        Assert.Equal(HttpStatusCode.Forbidden, await GetWithAsync(NotesWebFactory.CreateJwt(expires: DateTime.UtcNow.AddMinutes(-10))));

    [Fact]
    public async Task Token_signed_by_unknown_key_is_forbidden() =>
        Assert.Equal(HttpStatusCode.Forbidden, await GetWithAsync(NotesWebFactory.CreateJwt(key: new RsaSecurityKey(RSA.Create(2048)) { KeyId = "other" })));

    [Fact]
    public async Task Garbage_token_is_forbidden() =>
        Assert.Equal(HttpStatusCode.Forbidden, await GetWithAsync("not-a-jwt"));

    [Fact]
    public async Task Healthz_needs_no_token() =>
        Assert.Equal(HttpStatusCode.OK, await GetWithAsync(null, "/healthz"));

    [Fact]
    public async Task Sync_endpoints_are_protected() =>
        Assert.Equal(HttpStatusCode.Forbidden, await GetWithAsync(null, "/api/sync/changes?since=0"));
}
