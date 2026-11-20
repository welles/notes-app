using System.Net;
using System.Net.Http.Json;
using System.Text.Json.Nodes;

namespace Notes.Server.Tests;

public sealed class ApiCompatibilityTests
{
    [Fact]
    public async Task Meta_describes_server()
    {
        await using var factory = new NotesWebFactory();

        var meta = (await factory.CreateClient().GetFromJsonAsync<JsonObject>("/api/meta"))!;

        Assert.Equal(1, meta["protocol"]!["min"]!.GetValue<int>());
        Assert.Equal(1, meta["protocol"]!["max"]!.GetValue<int>());
        var types = meta["entityTypes"]!.AsArray().Select(t => t!.GetValue<string>()).ToList();
        Assert.Equal(["core.context", "stickies.sticky"], types);
        var caps = meta["capabilities"]!.AsArray().Select(t => t!.GetValue<string>()).ToList();
        Assert.Contains("sync.v1", caps);
        Assert.Contains("stickies", caps);
        Assert.DoesNotContain("convert", caps);
        Assert.Equal("1.2.0", meta["minClientVersion"]!["android"]!.GetValue<string>());
        Assert.False(string.IsNullOrEmpty(meta["serverVersion"]!.GetValue<string>()));
    }

    [Theory]
    [InlineData("0")]
    [InlineData("2")]
    [InlineData("abc")]
    public async Task Unsupported_protocol_gets_426(string protocol)
    {
        await using var factory = new NotesWebFactory();
        var http = factory.CreateClient();
        http.DefaultRequestHeaders.Add("Notes-Protocol", protocol);
        http.DefaultRequestHeaders.Add("Notes-Client", "android/9.9.9");

        var response = await http.GetAsync("/api/sync/changes?since=0");

        Assert.Equal(HttpStatusCode.UpgradeRequired, response.StatusCode);
    }

    [Fact]
    public async Task Supported_protocol_and_missing_header_are_accepted()
    {
        await using var factory = new NotesWebFactory();
        var http = factory.CreateClient();
        Assert.Equal(HttpStatusCode.OK, (await http.GetAsync("/api/sync/changes?since=0")).StatusCode);
        http.DefaultRequestHeaders.Add("Notes-Protocol", "1");
        Assert.Equal(HttpStatusCode.OK, (await http.GetAsync("/api/sync/changes?since=0")).StatusCode);
    }

    [Fact]
    public async Task OpenApi_document_is_served_and_matches_committed_spec()
    {
        await using var factory = new NotesWebFactory();

        var served = JsonNode.Parse(await factory.CreateClient().GetStringAsync("/api/openapi.json"))!;
        var committed = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(RepoRoot(), "api", "openapi.json")))!;

        Assert.True(JsonNode.DeepEquals(served["paths"], committed["paths"]), "api/openapi.json is outdated – rebuild Notes.Server and commit it.");
        Assert.True(JsonNode.DeepEquals(served["components"], committed["components"]), "api/openapi.json is outdated – rebuild Notes.Server and commit it.");
        Assert.NotNull(committed["components"]!["schemas"]!["StickyData"]);
        Assert.NotNull(committed["components"]!["schemas"]!["PushEnvelope"]);
    }

    internal static string RepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "flake.nix"))) dir = dir.Parent;
        return dir?.FullName ?? throw new InvalidOperationException("Repo root not found.");
    }
}

public sealed class ExportTests
{
    [Fact]
    public async Task Export_contains_all_live_entities_grouped_by_type()
    {
        await using var factory = new NotesWebFactory();
        var http = factory.CreateClient();
        var client = new SimClient(http, "c");
        client.Create("stickies.sticky", new() { ["contextId"] = Notes.Core.Contexts.Context.DefaultId.ToString(), ["content"] = "bleibt" });
        var gone = client.Create("stickies.sticky", new() { ["contextId"] = Notes.Core.Contexts.Context.DefaultId.ToString(), ["content"] = "weg" });
        await client.SyncAsync();
        client.Delete(gone);
        await client.SyncAsync();

        var response = await http.GetAsync("/api/export");
        var doc = System.Text.Json.Nodes.JsonNode.Parse(await response.Content.ReadAsStringAsync())!;

        Assert.StartsWith("attachment", response.Content.Headers.ContentDisposition?.DispositionType ?? "attachment");
        var stickies = doc["entities"]!["stickies.sticky"]!.AsArray();
        Assert.Equal(["bleibt"], stickies.Select(s => s!["content"]!.GetValue<string>()));
        Assert.Single(doc["entities"]!["core.context"]!.AsArray());
    }
}
