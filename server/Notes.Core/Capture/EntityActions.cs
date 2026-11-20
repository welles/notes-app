using Microsoft.EntityFrameworkCore;
using Notes.Core.Contexts;
using Notes.Core.Data;
using Notes.Core.Sync;

namespace Notes.Core.Capture;

/// <summary>
/// Cross-module core actions: quick capture, "convert to …", "move to context …" and listing a context across all types.
/// Everything is expressed as push envelopes, so the exact same mechanics work on clients (outbox entries with one batch id).
/// </summary>
public sealed class EntityActions(
    IDbContextFactory<NotesDbContext> dbFactory,
    SyncService sync,
    SyncHandlerRegistry handlers,
    CaptureTargetRegistry targets,
    TimeProvider time)
{
    /// <summary>Quick capture: creates a new entity in the given (or default) capture target.</summary>
    public async Task<EntityRef> CaptureAsync(CaptureContent content, Guid contextId, string? targetKey = null, CancellationToken ct = default)
    {
        var target = targetKey is null ? targets.Default : targets.Get(targetKey);
        var create = await BuildCreateAsync(target, content, contextId, ct);
        await sync.PushAsync([create], PushOrigin.Local, atomic: true, ct);
        return new EntityRef(create.EntityType, create.Id);
    }

    /// <summary>
    /// "Convert to …": exports the source as neutral content, creates it in the target (same context) and optionally deletes the source –
    /// atomically in one push.
    /// </summary>
    public async Task<EntityRef> ConvertAsync(EntityRef source, string targetKey, bool deleteSource, CancellationToken ct = default)
    {
        var handler = handlers.Get(source.EntityType);
        var target = targets.Get(targetKey);

        SyncEntity entity;
        await using (var db = await dbFactory.CreateDbContextAsync(ct))
            entity = await handler.FindAsync(db, source.Id, ct) is { IsDeleted: false } e
                ? e
                : throw new InvalidOperationException($"{source} does not exist.");

        var content = handler.Export(entity) ?? throw new InvalidOperationException($"{source.EntityType} cannot be exported.");
        var contextId = entity is ContextScopedEntity scoped ? scoped.ContextId : Context.DefaultId;

        var entries = new List<PushEnvelope> { await BuildCreateAsync(target, content with { Source = source }, contextId, ct) };
        if (deleteSource)
            entries.Add(new PushEnvelope(source.EntityType, source.Id, entity.Version, SyncTime.Now(time), true, null));

        await sync.PushAsync(entries, PushOrigin.Local, atomic: true, ct);
        return new EntityRef(entries[0].EntityType, entries[0].Id);
    }

    /// <summary>"Move to context …" for any context-scoped entity: only changes <c>contextId</c>.</summary>
    public Task MoveToContextAsync(EntityRef reference, Guid contextId, CancellationToken ct = default) =>
        MoveToContextAsync([reference], contextId, ct);

    public async Task MoveToContextAsync(IReadOnlyCollection<EntityRef> references, Guid contextId, CancellationToken ct = default)
    {
        var now = SyncTime.Now(time);
        var entries = new List<PushEnvelope>();
        await using (var db = await dbFactory.CreateDbContextAsync(ct))
        {
            foreach (var r in references)
            {
                var handler = handlers.Get(r.EntityType);
                if (!handler.IsContextScoped) throw new InvalidOperationException($"{r.EntityType} has no context.");
                var entity = await handler.FindAsync(db, r.Id, ct) ?? throw new InvalidOperationException($"{r} does not exist.");
                entries.Add(new PushEnvelope(r.EntityType, r.Id, entity.Version, now, false, new() { ["contextId"] = contextId.ToString() }));
            }
        }
        if (entries.Count > 0) await sync.PushAsync(entries, PushOrigin.Local, atomic: true, ct);
    }

    /// <summary>All non-deleted entities of all modules in a context.</summary>
    public async Task<IReadOnlyList<EntityRef>> ListInContextAsync(Guid contextId, CancellationToken ct = default)
    {
        await using var db = await dbFactory.CreateDbContextAsync(ct);
        var result = new List<EntityRef>();
        foreach (var handler in handlers.All.Where(h => h.IsContextScoped))
            result.AddRange((await handler.ListInContextAsync(db, contextId, ct)).Select(e => new EntityRef(handler.EntityType, e.Id)));
        return result;
    }

    private async Task<PushEnvelope> BuildCreateAsync(ICaptureTarget target, CaptureContent content, Guid contextId, CancellationToken ct)
    {
        System.Text.Json.Nodes.JsonObject data;
        await using (var db = await dbFactory.CreateDbContextAsync(ct))
            data = await target.CreateDataAsync(db, content, contextId, ct);
        data["contextId"] = contextId.ToString();
        var now = SyncTime.Now(time);
        data["createdAt"] = SyncTime.Format(now);
        return new PushEnvelope(target.EntityType, Guid.CreateVersion7(), null, now, false, data);
    }
}
