using System.Text.Json.Nodes;
using Microsoft.EntityFrameworkCore;
using Notes.Core.Contexts;
using Notes.Core.Data;
using Notes.Core.Events;

namespace Notes.Core.Sync;

/// <summary>
/// Generic sync protocol. Knows no modules, only entity types via <see cref="SyncHandlerRegistry"/>.
/// All writes – from clients and from the web UI – go through <see cref="PushAsync"/>, so they share
/// versioning, conflict resolution, context rules and live events.
/// </summary>
public sealed class SyncService(
    IDbContextFactory<NotesDbContext> dbFactory,
    SyncHandlerRegistry handlers,
    EventBus events,
    TimeProvider time)
{
    // SQLite has a single writer anyway; serialising in-process avoids SQLITE_BUSY between concurrent pushes.
    private readonly SemaphoreSlim _writeLock = new(1, 1);

    public async Task<PullResponse> PullAsync(long since, int limit, CancellationToken ct = default)
    {
        limit = Math.Clamp(limit, 1, 1000);
        await using var db = await dbFactory.CreateDbContextAsync(ct);
        var state = await db.SyncState.AsNoTracking().SingleAsync(ct);
        if (since > 0 && since < state.PurgedThroughVersion)
            return new PullResponse([], since, false, FullResyncRequired: true);

        var rows = await db.SyncChanges.AsNoTracking()
            .Where(c => c.Version > since)
            .OrderBy(c => c.Version)
            .Take(limit + 1)
            .ToListAsync(ct);
        var hasMore = rows.Count > limit;
        if (hasMore) rows.RemoveAt(rows.Count - 1);

        var envelopes = new List<SyncEnvelope>(rows.Count);
        foreach (var group in rows.GroupBy(r => r.EntityType))
        {
            if (handlers.Find(group.Key) is not { } handler) continue;
            var entities = await handler.LoadAsync(db, group.Select(r => r.EntityId).ToList(), ct);
            envelopes.AddRange(entities.Select(e => ToEnvelope(handler, e)));
        }
        envelopes.Sort((a, b) => a.Version.CompareTo(b.Version));

        return new PullResponse(envelopes, rows.Count > 0 ? rows[^1].Version : since, hasMore, false);
    }

    public async Task<SyncEnvelope?> GetAsync(EntityRef reference, CancellationToken ct = default)
    {
        if (handlers.Find(reference.EntityType) is not { } handler) return null;
        await using var db = await dbFactory.CreateDbContextAsync(ct);
        var entity = await handler.FindAsync(db, reference.Id, ct);
        return entity is null ? null : ToEnvelope(handler, entity);
    }

    /// <summary>
    /// Applies a push as a whole in one transaction. Core types are applied before module types, core deletions last
    /// (so "move everything out, then delete the context" works in one push).
    /// With <paramref name="atomic"/> the whole push is rolled back unless every entry is accepted.
    /// </summary>
    public async Task<PushResponse> PushAsync(IReadOnlyList<PushEnvelope> entries, PushOrigin origin, bool atomic = false, CancellationToken ct = default)
    {
        await _writeLock.WaitAsync(ct);
        try
        {
            await using var db = await dbFactory.CreateDbContextAsync(ct);
            await using var tx = await db.Database.BeginTransactionAsync(ct);

            var outcomes = new Outcome[entries.Count];
            foreach (var i in Enumerable.Range(0, entries.Count).OrderBy(i => Phase(entries[i])))
            {
                outcomes[i] = await ApplyAsync(db, entries[i], origin, ct);
                await db.SaveChangesAsync(ct);
            }

            if (atomic && outcomes.Any(o => o.Status != PushStatus.Accepted))
            {
                await tx.RollbackAsync(ct);
                throw new SyncRejectedException(outcomes.Select((o, i) => o.ToResult(entries[i], null)).ToList());
            }

            await tx.CommitAsync(ct);
            events.Publish(db.TakeSavedChanges());

            return new PushResponse(outcomes.Select((o, i) =>
                o.ToResult(entries[i], o.Entity is null ? null : ToEnvelope(handlers.Get(entries[i].EntityType), o.Entity))).ToList());
        }
        finally
        {
            _writeLock.Release();
        }
    }

    /// <summary>Hard-deletes tombstones (and push log entries) older than <paramref name="maxAge"/>.</summary>
    public async Task<int> PurgeTombstonesAsync(TimeSpan maxAge, CancellationToken ct = default)
    {
        var cutoff = SyncTime.Now(time) - maxAge;
        await _writeLock.WaitAsync(ct);
        try
        {
            await using var db = await dbFactory.CreateDbContextAsync(ct);
            await using var tx = await db.Database.BeginTransactionAsync(ct);
            var purged = new List<(Guid, long Version)>();
            foreach (var handler in handlers.All) purged.AddRange(await handler.PurgeTombstonesAsync(db, cutoff, ct));
            if (purged.Count > 0)
            {
                var max = purged.Max(p => p.Version);
                await db.SyncState.Where(s => s.Id == 1 && s.PurgedThroughVersion < max)
                    .ExecuteUpdateAsync(s => s.SetProperty(x => x.PurgedThroughVersion, max), ct);
            }
            await db.SyncPushLog.Where(l => l.LoggedAt < cutoff).ExecuteDeleteAsync(ct);
            await tx.CommitAsync(ct);
            return purged.Count;
        }
        finally
        {
            _writeLock.Release();
        }
    }

    private int Phase(PushEnvelope e)
    {
        var isCore = handlers.Find(e.EntityType) is { ApplyOrder: < 100 };
        return isCore ? (e.Deleted ? 2 : 0) : 1;
    }

    private async Task<Outcome> ApplyAsync(NotesDbContext db, PushEnvelope env, PushOrigin origin, CancellationToken ct)
    {
        if (handlers.Find(env.EntityType) is not { } handler)
            return Outcome.Rejected(RejectReasons.UnknownType);

        var modifiedAt = SyncTime.Normalize(env.ModifiedAt);
        var existing = await handler.FindAsync(db, env.Id, ct);

        if (origin == PushOrigin.Client)
        {
            // Idempotency: the same change (type, id, modifiedAt) was already processed – report, don't apply again.
            var logged = await db.SyncPushLog.FindAsync([env.EntityType, env.Id, modifiedAt], ct);
            if (logged is not null)
                return new Outcome(logged.Status, null, null, null, existing);
        }
        else if (existing is not null && env.BaseVersion == existing.Version && modifiedAt <= existing.ModifiedAt)
        {
            // Local (server-side) edits in quick succession: keep modifiedAt strictly increasing.
            modifiedAt = existing.ModifiedAt.AddMilliseconds(1);
        }

        Action<SyncEntity> apply;
        try
        {
            apply = handler.PrepareApply(env.Data ?? []);
        }
        catch (SyncDataException ex)
        {
            return Outcome.Rejected(RejectReasons.InvalidData, ex.Message);
        }

        // What the entity would look like with the client's change applied (used for validation and conflict copies).
        var proposed = existing is null ? handler.Create(env.Id) : handler.Clone(existing);
        apply(proposed);
        proposed.ModifiedAt = modifiedAt;
        if (proposed.CreatedAt == default) proposed.CreatedAt = modifiedAt;

        if (!env.Deleted && proposed is ContextScopedEntity scoped && !await EnsureContextAsync(db, scoped.ContextId, ct))
            return Outcome.Rejected(RejectReasons.UnknownContext);

        Outcome outcome;
        if (existing is null)
        {
            proposed.DeletedAt = env.Deleted ? modifiedAt : null;
            db.Add(proposed);
            outcome = Outcome.Accepted(proposed);
        }
        else if (env.BaseVersion == existing.Version)
        {
            if (env.Deleted)
            {
                if (await ContextDeletionBlockedAsync(db, existing, ct) is { } reason)
                    outcome = new Outcome(PushStatus.Conflict, reason, ConflictWinner.Server, null, existing);
                else
                {
                    existing.DeletedAt ??= modifiedAt;
                    existing.ModifiedAt = modifiedAt;
                    outcome = Outcome.Accepted(existing);
                }
            }
            else
            {
                apply(existing);
                existing.DeletedAt = null;
                existing.ModifiedAt = modifiedAt;
                outcome = Outcome.Accepted(existing);
            }
        }
        else
        {
            outcome = ResolveConflict(db, handler, env, existing, proposed, apply, modifiedAt);
        }

        if (origin == PushOrigin.Client && outcome.Status != PushStatus.Rejected)
            db.SyncPushLog.Add(new SyncPushLogEntry
            {
                EntityType = env.EntityType, EntityId = env.Id, ModifiedAt = modifiedAt, Status = outcome.Status, LoggedAt = SyncTime.Now(time),
            });
        return outcome;
    }

    private Outcome ResolveConflict(
        NotesDbContext db, ISyncHandler handler, PushEnvelope env, SyncEntity existing, SyncEntity proposed,
        Action<SyncEntity> apply, DateTime modifiedAt)
    {
        var serverDeleted = existing.IsDeleted;

        if (env.Deleted)
        {
            // Both deleted: nothing to resolve. Delete vs. edit: the edit wins.
            return serverDeleted
                ? Outcome.Accepted(existing)
                : new Outcome(PushStatus.Conflict, null, ConflictWinner.Server, null, existing);
        }

        if (serverDeleted)
        {
            // Edit vs. delete: the edit wins, the entity is restored.
            apply(existing);
            existing.DeletedAt = null;
            existing.ModifiedAt = modifiedAt;
            return new Outcome(PushStatus.Conflict, null, ConflictWinner.Client, null, existing);
        }

        // Edit vs. edit: last write wins on entity level (ties go to the server); the loser is kept as a conflict copy if it differs.
        var now = SyncTime.Now(time);
        if (modifiedAt > existing.ModifiedAt)
        {
            var loser = handler.Clone(existing);
            apply(existing);
            existing.ModifiedAt = modifiedAt;
            var copy = handler.NeedsConflictCopy(existing, loser) ? AddConflictCopy(db, handler, loser, now) : null;
            return new Outcome(PushStatus.Conflict, null, ConflictWinner.Client, copy, existing);
        }
        else
        {
            var copy = handler.NeedsConflictCopy(existing, proposed) ? AddConflictCopy(db, handler, proposed, now) : null;
            return new Outcome(PushStatus.Conflict, null, ConflictWinner.Server, copy, existing);
        }
    }

    private static EntityRef AddConflictCopy(NotesDbContext db, ISyncHandler handler, SyncEntity loser, DateTime now)
    {
        var copy = handler.CreateConflictCopy(loser, Guid.CreateVersion7(), now);
        db.Add(copy);
        return new EntityRef(handler.EntityType, copy.Id);
    }

    /// <summary>True if the context exists (a deleted one is implicitly restored so nothing becomes invisible).</summary>
    private async Task<bool> EnsureContextAsync(NotesDbContext db, Guid contextId, CancellationToken ct)
    {
        var context = await db.Contexts.FirstOrDefaultAsync(c => c.Id == contextId, ct);
        if (context is null) return false;
        if (context.IsDeleted)
        {
            context.DeletedAt = null;
            context.ModifiedAt = SyncTime.Now(time);
        }
        return true;
    }

    private async Task<string?> ContextDeletionBlockedAsync(NotesDbContext db, SyncEntity entity, CancellationToken ct)
    {
        if (entity is not Context context || context.IsDeleted) return null;
        if (context.Id == Context.DefaultId) return ConflictReasons.DefaultContext;
        foreach (var h in handlers.All.Where(h => h.IsContextScoped))
            if ((await h.ListInContextAsync(db, context.Id, ct)).Count > 0)
                return ConflictReasons.ContextNotEmpty;
        return null;
    }

    private static SyncEnvelope ToEnvelope(ISyncHandler handler, SyncEntity e) =>
        new(handler.EntityType, e.Id, e.Version, e.IsDeleted, handler.Serialize(e));

    private sealed record Outcome(PushStatus Status, string? Reason, ConflictWinner? Winner, EntityRef? ConflictCopy, SyncEntity? Entity, string? Detail = null)
    {
        public static Outcome Accepted(SyncEntity entity) => new(PushStatus.Accepted, null, null, null, entity);
        public static Outcome Rejected(string reason, string? detail = null) => new(PushStatus.Rejected, reason, null, null, null, detail);

        public PushEntryResult ToResult(PushEnvelope env, SyncEnvelope? server) =>
            new(env.EntityType, env.Id, Status, Detail is null ? Reason : $"{Reason}: {Detail}", Winner, ConflictCopy, server);
    }
}
