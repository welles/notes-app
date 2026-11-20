using System.ComponentModel;
using Notes.Core.Sync;

namespace Notes.Server.Api;

/// <summary>Body of <c>POST /api/sync/push</c>.</summary>
public sealed record PushRequest([property: Description("Changes to apply as a whole, in one transaction.")] IReadOnlyList<PushEnvelope> Changes);

public static class SyncEndpoints
{
    public const int DefaultLimit = 500;

    public static void MapSyncEndpoints(this IEndpointRouteBuilder app)
    {
        var sync = app.MapGroup("/api/sync").WithTags("Sync");

        sync.MapGet("/changes", (long? since, int? limit, SyncService service, CancellationToken ct) =>
                service.PullAsync(since ?? 0, limit ?? DefaultLimit, ct))
            .WithName("PullChanges")
            .WithSummary("Changes of all entity types since a cursor, ordered by version.")
            .WithDescription("If fullResyncRequired is true, the client must reload everything (since=0) while keeping its outbox.");

        sync.MapPost("/push", async (PushRequest request, SyncService service, CancellationToken ct) =>
                await service.PushAsync(request.Changes, PushOrigin.Client, ct: ct))
            .WithName("PushChanges")
            .WithSummary("Applies client changes atomically; returns a result and the resulting server state per entry.");
    }
}
