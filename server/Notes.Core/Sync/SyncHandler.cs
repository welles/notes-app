using System.Text.Json.Nodes;
using Microsoft.EntityFrameworkCore;
using Notes.Core.Capture;
using Notes.Core.Data;

namespace Notes.Core.Sync;

/// <summary>
/// Base implementation of <see cref="ISyncHandler"/>. Handles the common sync fields, <c>contextId</c>,
/// merge semantics and preservation of unknown fields. Modules only describe their own fields.
/// </summary>
public abstract class SyncHandler<T> : ISyncHandler where T : SyncEntity, new()
{
    private static readonly string[] CommonFields = ["id", "createdAt", "modifiedAt", "deletedAt", "version", "contextId"];
    private HashSet<string>? _known;

    public string EntityType { get; } = SyncEntityTypeAttribute.Of(typeof(T));
    public Type ClrType => typeof(T);
    public virtual int ApplyOrder => 100;
    public bool IsContextScoped { get; } = typeof(ContextScopedEntity).IsAssignableFrom(typeof(T));

    /// <summary>JSON names of the module-specific fields.</summary>
    protected abstract IReadOnlyCollection<string> Fields { get; }

    /// <summary>Writes the module-specific fields into <paramref name="data"/>.</summary>
    protected abstract void Write(T entity, JsonObject data);

    /// <summary>Parses the module-specific fields present in <paramref name="data"/> (throwing on bad input) and returns the mutation.</summary>
    protected abstract Action<T> Parse(JsonObject data);

    protected virtual bool NeedsConflictCopy(T winner, T loser) => false;
    protected virtual void PrepareConflictCopy(T copy) { }
    protected virtual CaptureContent? Export(T entity) => null;

    private HashSet<string> Known => _known ??= [.. CommonFields, .. Fields];

    public async Task<SyncEntity?> FindAsync(NotesDbContext db, Guid id, CancellationToken ct) =>
        await db.Set<T>().FirstOrDefaultAsync(e => e.Id == id, ct);

    public async Task<IReadOnlyList<SyncEntity>> LoadAsync(NotesDbContext db, IReadOnlyCollection<Guid> ids, CancellationToken ct) =>
        await db.Set<T>().Where(e => ids.Contains(e.Id)).ToListAsync(ct);

    public async Task<IReadOnlyList<SyncEntity>> ListInContextAsync(NotesDbContext db, Guid contextId, CancellationToken ct)
    {
        if (!IsContextScoped) return [];
        return await db.Set<T>()
            .Where(e => e.DeletedAt == null && EF.Property<Guid>(e, nameof(ContextScopedEntity.ContextId)) == contextId)
            .ToListAsync(ct);
    }

    public SyncEntity Create(Guid id) => new T { Id = id };

    public JsonObject Serialize(SyncEntity entity)
    {
        var e = (T)entity;
        var data = e.ExtraJson is { } extra ? JsonNode.Parse(extra)!.AsObject() : [];
        data["id"] = e.Id.ToString();
        data["createdAt"] = JsonFields.Value(e.CreatedAt);
        data["modifiedAt"] = JsonFields.Value(e.ModifiedAt);
        data["deletedAt"] = JsonFields.Value(e.DeletedAt);
        data["version"] = e.Version;
        if (e is ContextScopedEntity scoped) data["contextId"] = scoped.ContextId.ToString();
        Write(e, data);
        return data;
    }

    public Action<SyncEntity> PrepareApply(JsonObject data)
    {
        Guid? contextId = null;
        if (IsContextScoped && JsonFields.Has(data, "contextId", out var ctxNode))
            contextId = JsonFields.Guid(ctxNode, "contextId");
        DateTime? createdAt = JsonFields.Has(data, "createdAt", out var createdNode) ? JsonFields.DateTime(createdNode, "createdAt") : null;

        var apply = Parse(data);

        var unknown = data.Where(p => !Known.Contains(p.Key)).Select(p => (p.Key, Value: p.Value?.DeepClone())).ToList();

        return entity =>
        {
            var e = (T)entity;
            if (contextId is { } c) ((ContextScopedEntity)(SyncEntity)e).ContextId = c;
            if (createdAt is { } ca && e.CreatedAt == default) e.CreatedAt = ca;
            apply(e);
            if (unknown.Count > 0)
            {
                var extra = e.ExtraJson is { } x ? JsonNode.Parse(x)!.AsObject() : [];
                foreach (var (key, value) in unknown)
                {
                    if (value is null) extra.Remove(key);
                    else extra[key] = value.DeepClone();
                }
                e.ExtraJson = extra.Count == 0 ? null : extra.ToJsonString();
            }
        };
    }

    public SyncEntity Clone(SyncEntity entity)
    {
        var copy = new T
        {
            Id = entity.Id,
            CreatedAt = entity.CreatedAt,
            ModifiedAt = entity.ModifiedAt,
            DeletedAt = entity.DeletedAt,
            Version = entity.Version,
            ExtraJson = entity.ExtraJson,
        };
        PrepareApply(Serialize(entity))(copy);
        return copy;
    }

    bool ISyncHandler.NeedsConflictCopy(SyncEntity winner, SyncEntity loser) => NeedsConflictCopy((T)winner, (T)loser);

    public SyncEntity CreateConflictCopy(SyncEntity loser, Guid newId, DateTime now)
    {
        var copy = (T)Clone(loser);
        copy.Id = newId;
        copy.CreatedAt = now;
        copy.ModifiedAt = now;
        copy.DeletedAt = null;
        copy.Version = 0;
        PrepareConflictCopy(copy);
        return copy;
    }

    CaptureContent? ISyncHandler.Export(SyncEntity entity) => Export((T)entity);

    public async Task<IReadOnlyList<(Guid Id, long Version)>> PurgeTombstonesAsync(NotesDbContext db, DateTime cutoff, CancellationToken ct)
    {
        var query = db.Set<T>().Where(e => e.DeletedAt != null && e.DeletedAt < cutoff);
        var purged = await query.Select(e => new { e.Id, e.Version }).ToListAsync(ct);
        if (purged.Count == 0) return [];
        var ids = purged.Select(p => p.Id).ToList();
        await db.SyncChanges.Where(c => c.EntityType == EntityType && ids.Contains(c.EntityId)).ExecuteDeleteAsync(ct);
        await query.ExecuteDeleteAsync(ct);
        return purged.Select(p => (p.Id, p.Version)).ToList();
    }
}
