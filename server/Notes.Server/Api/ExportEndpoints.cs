using System.Text.Json.Nodes;
using Notes.Core.Sync;

namespace Notes.Server.Api;

public static class ExportEndpoints
{
    /// <summary>All data of all modules as one JSON document (via the sync handlers – no module-specific code).</summary>
    public static void MapExportEndpoints(this IEndpointRouteBuilder app) =>
        app.MapGet("/api/export", async (SyncService sync, TimeProvider time, CancellationToken ct) =>
            {
                var byType = new SortedDictionary<string, JsonArray>(StringComparer.Ordinal);
                long cursor = 0;
                while (true)
                {
                    var page = await sync.PullAsync(cursor, 1000, ct);
                    foreach (var e in page.Changes.Where(c => !c.Deleted))
                    {
                        if (!byType.TryGetValue(e.EntityType, out var list)) byType[e.EntityType] = list = [];
                        list.Add(e.Data.DeepClone());
                    }
                    cursor = page.Cursor;
                    if (!page.HasMore) break;
                }
                var now = time.GetUtcNow();
                var doc = new JsonObject
                {
                    ["exportedAt"] = SyncTime.Format(now.UtcDateTime),
                    ["serverVersion"] = MetaEndpoints.ServerVersion,
                    ["entities"] = new JsonObject(byType.Select(kv => KeyValuePair.Create(kv.Key, (JsonNode?)kv.Value))),
                };
                return Results.File(System.Text.Encoding.UTF8.GetBytes(doc.ToJsonString(new System.Text.Json.JsonSerializerOptions { WriteIndented = true })),
                    "application/json", $"notes-export-{now:yyyy-MM-dd}.json");
            })
            .WithTags("Export")
            .WithName("ExportAll")
            .WithSummary("All non-deleted entities of all modules as JSON, grouped by entity type.");
}
