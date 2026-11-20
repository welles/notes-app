using System.Net.Http.Json;
using System.Text.Json.Nodes;

namespace Notes.Server.Tests;

/// <summary>
/// Minimal simulated sync client over HTTP: local store with raw JSON, outbox, push-then-pull with paging and full resync.
/// Mirrors what the Kotlin sync engine does.
/// </summary>
public sealed class SimClient(HttpClient http, string name, int pageSize = 500)
{
    public sealed record Local(string EntityType, Guid Id, long Version, bool Deleted, JsonObject Data);
    public sealed record Pending(string EntityType, Guid Id, long? BaseVersion, DateTime ModifiedAt, bool Deleted, JsonObject Data);

    public Dictionary<Guid, Local> Store { get; } = [];
    public Dictionary<Guid, Pending> Outbox { get; } = [];
    public HashSet<Guid> WaitingForServerUpdate { get; } = [];
    public long Cursor { get; private set; }
    public int FullResyncs { get; private set; }
    public DateTime Clock { get; set; } = new(2026, 11, 20, 8, 0, 0, DateTimeKind.Utc);
    public string Name { get; } = name;

    private DateTime Tick() => Clock = Clock.AddSeconds(1);

    public Guid Create(string entityType, JsonObject data)
    {
        var id = Guid.CreateVersion7();
        var now = Tick();
        data["id"] = id.ToString();
        data["createdAt"] = Iso(now);
        Store[id] = new Local(entityType, id, 0, false, data);
        Outbox[id] = new Pending(entityType, id, null, now, false, (JsonObject)data.DeepClone());
        return id;
    }

    /// <summary>Local edit: patch is merged into the raw JSON, the outbox entry keeps the base version of the first unsynced change.</summary>
    public void Edit(Guid id, JsonObject patch, bool sendFullRaw = true)
    {
        var local = Store[id];
        var data = (JsonObject)local.Data.DeepClone();
        foreach (var (k, v) in patch) data[k] = v?.DeepClone();
        Store[id] = local with { Data = data, Deleted = false };
        var now = Tick();
        var baseVersion = Outbox.TryGetValue(id, out var p) ? p.BaseVersion : local.Version;
        Outbox[id] = new Pending(local.EntityType, id, baseVersion, now, false, sendFullRaw ? (JsonObject)data.DeepClone() : (JsonObject)patch.DeepClone());
    }

    public void Delete(Guid id)
    {
        var local = Store[id];
        Store[id] = local with { Deleted = true };
        var baseVersion = Outbox.TryGetValue(id, out var p) ? p.BaseVersion : local.Version;
        Outbox[id] = new Pending(local.EntityType, id, baseVersion, Tick(), true, (JsonObject)local.Data.DeepClone());
    }

    public async Task SyncAsync()
    {
        await PushAsync();
        await PullAsync();
    }

    public async Task<JsonArray> PushAsync()
    {
        if (Outbox.Count == 0) return [];
        var changes = new JsonArray(Outbox.Values.Select(p => (JsonNode)new JsonObject
        {
            ["entityType"] = p.EntityType,
            ["id"] = p.Id.ToString(),
            ["baseVersion"] = p.BaseVersion,
            ["modifiedAt"] = Iso(p.ModifiedAt),
            ["deleted"] = p.Deleted,
            ["data"] = p.Data.DeepClone(),
        }).ToArray());
        var response = await http.PostAsJsonAsync("/api/sync/push", new JsonObject { ["changes"] = changes });
        response.EnsureSuccessStatusCode();
        var results = (await response.Content.ReadFromJsonAsync<JsonObject>())!["results"]!.AsArray();
        foreach (var r in results)
        {
            var id = Guid.Parse(r!["id"]!.GetValue<string>());
            if (r["status"]!.GetValue<string>() == "rejected")
            {
                WaitingForServerUpdate.Add(id); // keep in outbox, retry later
                continue;
            }
            Outbox.Remove(id);
            WaitingForServerUpdate.Remove(id);
            if (r["server"] is JsonObject server) Apply(server);
        }
        return results;
    }

    public async Task PullAsync()
    {
        while (true)
        {
            var page = (await http.GetFromJsonAsync<JsonObject>($"/api/sync/changes?since={Cursor}&limit={pageSize}"))!;
            if (page["fullResyncRequired"]!.GetValue<bool>())
            {
                FullResyncs++;
                Cursor = 0;
                foreach (var id in Store.Keys.Where(id => !Outbox.ContainsKey(id)).ToList()) Store.Remove(id);
                continue;
            }
            foreach (var e in page["changes"]!.AsArray()) Apply(e!.AsObject());
            Cursor = page["cursor"]!.GetValue<long>();
            if (!page["hasMore"]!.GetValue<bool>()) return;
        }
    }

    private void Apply(JsonObject envelope)
    {
        var id = Guid.Parse(envelope["id"]!.GetValue<string>());
        if (Outbox.ContainsKey(id)) return; // local unsynced change wins locally until pushed
        Store[id] = new Local(
            envelope["entityType"]!.GetValue<string>(), id, envelope["version"]!.GetValue<long>(),
            envelope["deleted"]!.GetValue<bool>(), (JsonObject)envelope["data"]!.DeepClone());
    }

    public static string Iso(DateTime dt) => dt.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");
}
