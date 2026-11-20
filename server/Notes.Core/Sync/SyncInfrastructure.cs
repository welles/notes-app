namespace Notes.Core.Sync;

/// <summary>Row in <c>sync_changes</c>: latest version per entity (upserted on every change).</summary>
public sealed class SyncChange
{
    public required string EntityType { get; set; }
    public Guid EntityId { get; set; }
    public long Version { get; set; }
}

/// <summary>Single-row table <c>sync_state</c>: version sequence and tombstone purge watermark.</summary>
public sealed class SyncState
{
    public int Id { get; set; }
    public long LastVersion { get; set; }

    /// <summary>Highest version of any tombstone that was purged. Cursors below this need a full resync.</summary>
    public long PurgedThroughVersion { get; set; }
}

/// <summary>Row in <c>sync_push_log</c>: makes push entries idempotent by (entityType, id, modifiedAt).</summary>
public sealed class SyncPushLogEntry
{
    public required string EntityType { get; set; }
    public Guid EntityId { get; set; }
    public DateTime ModifiedAt { get; set; }
    public PushStatus Status { get; set; }
    public DateTime LoggedAt { get; set; }
}
