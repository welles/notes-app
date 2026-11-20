using System.Threading.Channels;
using Notes.Core.Events;
using Notes.Core.Sync;

namespace Notes.Server.Api;

/// <summary>
/// Live sync hint for clients in the foreground (Server-Sent Events). The stream carries no data, only
/// "something changed, latest version is N"; clients then use the normal pull. Capability: <c>sync.events</c>.
/// </summary>
public static class SyncEventsEndpoint
{
    /// <summary>Keeps the connection alive through proxies (Cloudflare closes idle connections after ~100 s).</summary>
    public static TimeSpan Heartbeat { get; set; } = TimeSpan.FromSeconds(25);

    public static void MapSyncEventsEndpoint(this IEndpointRouteBuilder app) =>
        app.MapGet("/api/sync/events", async (HttpContext http, SyncService sync, EventBus events, IHostApplicationLifetime lifetime, CancellationToken requestAborted) =>
            {
                // End streams immediately on shutdown – otherwise Kestrel keeps them open until its shutdown timeout
                // and clients stay attached to a dying server instead of reconnecting to the new one.
                using var linked = CancellationTokenSource.CreateLinkedTokenSource(requestAborted, lifetime.ApplicationStopping);
                var ct = linked.Token;
                http.Response.ContentType = "text/event-stream";
                http.Response.Headers.CacheControl = "no-cache";
                http.Response.Headers["X-Accel-Buffering"] = "no";

                // Only the latest version matters; older pending hints are dropped.
                var versions = Channel.CreateBounded<long>(new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropOldest });
                using var subscription = events.Subscribe(changes => versions.Writer.TryWrite(changes.Max(c => c.Version)));

                // Initial event: lets a (re)connecting client catch up on what it missed.
                await WriteAsync(http, $"event: changed\ndata: {await sync.GetLatestVersionAsync(ct)}\n\n", ct);
                try
                {
                while (!ct.IsCancellationRequested)
                {
                    using var wait = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    wait.CancelAfter(Heartbeat);
                    try
                    {
                        var version = await versions.Reader.ReadAsync(wait.Token);
                        await WriteAsync(http, $"event: changed\ndata: {version}\n\n", ct);
                    }
                    catch (OperationCanceledException) when (!ct.IsCancellationRequested)
                    {
                        await WriteAsync(http, ": ping\n\n", ct);
                    }
                }
                }
                catch (OperationCanceledException) when (lifetime.ApplicationStopping.IsCancellationRequested)
                {
                    // shutting down: just end the response
                }
            })
            .WithTags("Sync")
            .WithName("SyncEvents")
            .WithSummary("Server-Sent Events: 'changed' with the latest version whenever data changes; comment heartbeats every 25 s.")
            .Produces(StatusCodes.Status200OK, contentType: "text/event-stream");

    private static async Task WriteAsync(HttpContext http, string text, CancellationToken ct)
    {
        await http.Response.WriteAsync(text, ct);
        await http.Response.Body.FlushAsync(ct);
    }
}
