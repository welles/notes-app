using System.Text.Json.Nodes;
using Notes.Core.Capture;
using Notes.Core.Data;

namespace Notes.Core.Sync;

/// <summary>
/// Per-entity-type plug-in for the generic sync. Modules implement it by deriving from <see cref="SyncHandler{T}"/>.
/// The sync protocol only ever talks to this interface, never to concrete modules.
/// </summary>
public interface ISyncHandler
{
    string EntityType { get; }
    Type ClrType { get; }

    /// <summary>Order in which entries of a push are applied. Core types (contexts) come first.</summary>
    int ApplyOrder { get; }

    bool IsContextScoped { get; }

    /// <summary>Documentation type describing <c>data</c> of this entity type in the OpenAPI spec (API contract only).</summary>
    Type? DataSchema { get; }

    Task<SyncEntity?> FindAsync(NotesDbContext db, Guid id, CancellationToken ct);
    Task<IReadOnlyList<SyncEntity>> LoadAsync(NotesDbContext db, IReadOnlyCollection<Guid> ids, CancellationToken ct);

    /// <summary>Non-deleted entities in the given context (empty for types that are not context-scoped).</summary>
    Task<IReadOnlyList<SyncEntity>> ListInContextAsync(NotesDbContext db, Guid contextId, CancellationToken ct);

    SyncEntity Create(Guid id);
    JsonObject Serialize(SyncEntity entity);

    /// <summary>
    /// Validates <paramref name="data"/> completely and returns an action that merges it into an entity
    /// (missing fields unchanged, explicit null clears, unknown fields preserved). Throws <see cref="SyncDataException"/>
    /// before anything is mutated.
    /// </summary>
    Action<SyncEntity> PrepareApply(JsonObject data);

    SyncEntity Clone(SyncEntity entity);

    /// <summary>Whether the loser of a last-write-wins conflict differs enough from the winner to keep a copy.</summary>
    bool NeedsConflictCopy(SyncEntity winner, SyncEntity loser);

    /// <summary>New entity (new id, not yet added) holding the loser's content, marked as conflict copy.</summary>
    SyncEntity CreateConflictCopy(SyncEntity loser, Guid newId, DateTime now);

    /// <summary>Neutral content for "convert to …". Null if the type cannot be exported.</summary>
    CaptureContent? Export(SyncEntity entity);

    /// <summary>Hard-deletes tombstones older than <paramref name="cutoff"/>; returns the purged (id, version) pairs.</summary>
    Task<IReadOnlyList<(Guid Id, long Version)>> PurgeTombstonesAsync(NotesDbContext db, DateTime cutoff, CancellationToken ct);
}
