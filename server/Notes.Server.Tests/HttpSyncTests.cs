using System.Net;
using System.Net.Http.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.DependencyInjection;
using Notes.Core.Contexts;
using Notes.Core.Sync;

namespace Notes.Server.Tests;

public sealed class HttpSyncTests
{
    private static JsonObject StickyData(string content, string? title = null, Guid? context = null) => new()
    {
        ["contextId"] = (context ?? Context.DefaultId).ToString(),
        ["title"] = title,
        ["content"] = content,
        ["color"] = "yellow",
        ["pinned"] = false,
        ["position"] = 0,
    };

    private static void AssertConverged(SimClient a, SimClient b)
    {
        var left = a.Store.Values.Where(e => !e.Deleted).OrderBy(e => e.Id).Select(e => (e.Id, e.Version, e.Data.ToJsonString())).ToList();
        var right = b.Store.Values.Where(e => !e.Deleted).OrderBy(e => e.Id).Select(e => (e.Id, e.Version, e.Data.ToJsonString())).ToList();
        Assert.Equal(left, right);
        Assert.Empty(a.Outbox);
        Assert.Empty(b.Outbox);
    }

    [Fact]
    public async Task Two_clients_with_offline_phases_converge()
    {
        await using var factory = new NotesWebFactory();
        var phone = new SimClient(factory.CreateClient(), "phone", pageSize: 3);
        var desktop = new SimClient(factory.CreateClient(), "desktop", pageSize: 2);
        desktop.Clock = phone.Clock.AddMilliseconds(500);

        // Both online: phone creates, desktop receives.
        var shared = phone.Create(Sticky, StickyData("gemeinsam", "Titel"));
        var keep = phone.Create(Sticky, StickyData("bleibt"));
        await phone.SyncAsync();
        await desktop.SyncAsync();

        // Offline phase: both edit the same post-it, the phone deletes another one the desktop edits, both create new ones.
        phone.Edit(shared, new() { ["content"] = "vom Handy" });
        phone.Delete(keep);
        for (var i = 0; i < 4; i++) phone.Create(Sticky, StickyData($"handy {i}"));
        desktop.Edit(shared, new() { ["content"] = "vom Desktop" });
        desktop.Edit(keep, new() { ["pinned"] = true });
        var ctx = desktop.Create(Context.TypeName, new() { ["name"] = "Arbeit", ["position"] = 1 });
        desktop.Create(Sticky, StickyData("im neuen Kontext", context: ctx));

        await phone.SyncAsync();
        await desktop.SyncAsync();
        await phone.SyncAsync();

        AssertConverged(phone, desktop);
        var texts = phone.Store.Values.Where(e => e.EntityType == Sticky && !e.Deleted).Select(e => e.Data["content"]!.GetValue<string>()).ToList();
        Assert.Contains("vom Desktop", texts);   // newer edit wins
        Assert.Contains("vom Handy", texts);     // loser kept as conflict copy
        Assert.Contains("bleibt", texts);        // edit beats delete
        Assert.Contains("im neuen Kontext", texts);
        Assert.Equal(4, texts.Count(t => t.StartsWith("handy ")));
        Assert.Contains(phone.Store.Values, e => e.Id == ctx && e.Data["name"]!.GetValue<string>() == "Arbeit");
    }

    [Fact]
    public async Task Unknown_entity_type_stays_in_outbox_rest_is_synced()
    {
        await using var factory = new NotesWebFactory();
        var newerClient = new SimClient(factory.CreateClient(), "newer");
        var todo = newerClient.Create("todos.todo", new() { ["contextId"] = Context.DefaultId.ToString(), ["text"] = "x" });
        var sticky = newerClient.Create(Sticky, StickyData("a"));

        var results = await newerClient.PushAsync();

        Assert.Equal("rejected", results.Single(r => r!["id"]!.GetValue<string>() == todo.ToString())!["status"]!.GetValue<string>());
        Assert.Equal("unknown_type", results.Single(r => r!["id"]!.GetValue<string>() == todo.ToString())!["reason"]!.GetValue<string>());
        Assert.True(newerClient.Outbox.ContainsKey(todo));
        Assert.Contains(todo, newerClient.WaitingForServerUpdate);
        Assert.False(newerClient.Outbox.ContainsKey(sticky));

        var other = new SimClient(factory.CreateClient(), "other");
        await other.SyncAsync();
        Assert.Contains(sticky, other.Store.Keys);
        Assert.DoesNotContain(todo, other.Store.Keys);
    }

    [Fact]
    public async Task Old_cursor_after_tombstone_purge_triggers_full_resync()
    {
        await using var factory = new NotesWebFactory();
        var a = new SimClient(factory.CreateClient(), "a");
        var b = new SimClient(factory.CreateClient(), "b");
        var gone = a.Create(Sticky, StickyData("weg"));
        var stays = a.Create(Sticky, StickyData("bleibt"));
        await a.SyncAsync();
        await b.SyncAsync();
        a.Delete(gone);
        await a.SyncAsync();

        // b was offline for a long time; meanwhile a local, unsynced change was made on b.
        var offline = b.Create(Sticky, StickyData("offline auf b"));
        await factory.Services.GetRequiredService<SyncService>().PurgeTombstonesAsync(TimeSpan.Zero);

        await b.SyncAsync();

        Assert.Equal(1, b.FullResyncs);
        Assert.DoesNotContain(gone, b.Store.Keys);
        Assert.Contains(stays, b.Store.Keys);
        Assert.Contains(offline, b.Store.Keys);
        await a.SyncAsync();
        AssertConverged(a, b);
    }

    [Fact]
    public async Task Push_without_a_known_field_does_not_clear_it()
    {
        await using var factory = new NotesWebFactory();
        var client = new SimClient(factory.CreateClient(), "c");
        var id = client.Create(Sticky, StickyData("text", "Titel"));
        await client.SyncAsync();

        // An (older) client sends only the field it changed.
        client.Edit(id, new() { ["pinned"] = true }, sendFullRaw: false);
        await client.SyncAsync();

        var data = client.Store[id].Data;
        Assert.Equal("Titel", data["title"]!.GetValue<string>());
        Assert.Equal("text", data["content"]!.GetValue<string>());
        Assert.True(data["pinned"]!.GetValue<bool>());
    }

    [Fact]
    public async Task Repeated_push_after_network_failure_is_idempotent()
    {
        await using var factory = new NotesWebFactory();
        var http = factory.CreateClient();
        var id = Guid.CreateVersion7();
        var body = new JsonObject
        {
            ["changes"] = new JsonArray(new JsonObject
            {
                ["entityType"] = Sticky, ["id"] = id.ToString(), ["baseVersion"] = null,
                ["modifiedAt"] = "2026-11-20T10:00:00.000Z", ["deleted"] = false, ["data"] = StickyData("a"),
            }),
        };

        var first = await (await http.PostAsJsonAsync("/api/sync/push", body)).Content.ReadFromJsonAsync<JsonObject>();
        var second = await (await http.PostAsJsonAsync("/api/sync/push", body)).Content.ReadFromJsonAsync<JsonObject>();

        Assert.Equal(first!["results"]![0]!["server"]!["version"]!.GetValue<long>(), second!["results"]![0]!["server"]!["version"]!.GetValue<long>());
    }

    [Fact]
    public async Task Malformed_push_returns_400_not_500()
    {
        await using var factory = new NotesWebFactory();
        var response = await factory.CreateClient().PostAsync("/api/sync/push", new StringContent("{\"changes\": [{\"id\": 5}]}", System.Text.Encoding.UTF8, "application/json"));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    private const string Sticky = "stickies.sticky";
}
