using System.Diagnostics;
using System.Net.Http.Json;
using System.Text.Json.Nodes;
using Notes.Core.Contexts;
using Notes.Server.Api;

namespace Notes.Server.Tests;

public sealed class SyncEventsTests
{
    private static async Task<string?> NextEventAsync(StreamReader reader, TimeSpan timeout)
    {
        using var cts = new CancellationTokenSource(timeout);
        var lines = new List<string>();
        while (true)
        {
            var line = await reader.ReadLineAsync(cts.Token);
            if (line is null) return null;
            if (line.Length == 0)
            {
                if (lines.Count > 0) return string.Join("\n", lines);
                continue;
            }
            lines.Add(line);
        }
    }

    [Fact]
    public async Task Stream_sends_initial_version_then_each_change()
    {
        await using var factory = new NotesWebFactory();
        var http = factory.CreateClient();
        using var response = await http.GetAsync("/api/sync/events", HttpCompletionOption.ResponseHeadersRead);
        Assert.Equal("text/event-stream", response.Content.Headers.ContentType?.MediaType);
        using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());

        var initial = await NextEventAsync(reader, TimeSpan.FromSeconds(5));
        Assert.Equal("event: changed\ndata: 1", initial);

        var watch = Stopwatch.StartNew();
        var client = new SimClient(factory.CreateClient(), "other");
        client.Create("stickies.sticky", new JsonObject { ["contextId"] = Context.DefaultId.ToString(), ["content"] = "live" });
        await client.PushAsync();

        var next = await NextEventAsync(reader, TimeSpan.FromSeconds(5));
        Assert.Equal("event: changed\ndata: 2", next);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(2), $"took {watch.Elapsed}");
    }

    [Fact]
    public async Task Idle_stream_sends_heartbeats()
    {
        var previous = SyncEventsEndpoint.Heartbeat;
        SyncEventsEndpoint.Heartbeat = TimeSpan.FromMilliseconds(200);
        try
        {
            await using var factory = new NotesWebFactory();
            using var response = await factory.CreateClient().GetAsync("/api/sync/events", HttpCompletionOption.ResponseHeadersRead);
            using var reader = new StreamReader(await response.Content.ReadAsStreamAsync());
            await NextEventAsync(reader, TimeSpan.FromSeconds(5));

            Assert.Equal(": ping", await NextEventAsync(reader, TimeSpan.FromSeconds(5)));
        }
        finally
        {
            SyncEventsEndpoint.Heartbeat = previous;
        }
    }

    [Fact]
    public async Task Meta_announces_the_capability()
    {
        await using var factory = new NotesWebFactory();
        var meta = (await factory.CreateClient().GetFromJsonAsync<JsonObject>("/api/meta"))!;
        Assert.Contains("sync.events", meta["capabilities"]!.AsArray().Select(c => c!.GetValue<string>()));
    }
}
