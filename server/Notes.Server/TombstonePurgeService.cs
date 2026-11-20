using Notes.Core.Sync;

namespace Notes.Server;

/// <summary>Purges tombstones older than 90 days once a day. Clients with older cursors then get "full resync required".</summary>
internal sealed class TombstonePurgeService(SyncService sync, ILogger<TombstonePurgeService> log) : BackgroundService
{
    public static readonly TimeSpan MaxAge = TimeSpan.FromDays(90);

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromDays(1));
        do
        {
            try
            {
                var purged = await sync.PurgeTombstonesAsync(MaxAge, ct);
                if (purged > 0) log.LogInformation("Purged {Count} tombstones", purged);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                log.LogError(ex, "Tombstone purge failed");
            }
        } while (await timer.WaitForNextTickAsync(ct));
    }
}
