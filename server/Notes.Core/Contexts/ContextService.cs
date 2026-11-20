using System.Text.Json.Nodes;
using Microsoft.EntityFrameworkCore;
using Notes.Core.Capture;
using Notes.Core.Data;
using Notes.Core.Sync;

namespace Notes.Core.Contexts;

/// <summary>Managing contexts from the web UI. Uses the generic push, so rules are identical for clients.</summary>
public sealed class ContextService(
    IDbContextFactory<NotesDbContext> dbFactory,
    SyncService sync,
    EntityActions actions,
    TimeProvider time)
{
    public async Task<IReadOnlyList<Context>> ListAsync(CancellationToken ct = default)
    {
        await using var db = await dbFactory.CreateDbContextAsync(ct);
        return await db.Contexts.AsNoTracking().Where(c => c.DeletedAt == null)
            .OrderBy(c => c.Position).ThenBy(c => c.Name).ToListAsync(ct);
    }

    public async Task<Context?> GetAsync(Guid id, CancellationToken ct = default)
    {
        await using var db = await dbFactory.CreateDbContextAsync(ct);
        return await db.Contexts.AsNoTracking().FirstOrDefaultAsync(c => c.Id == id && c.DeletedAt == null, ct);
    }

    public async Task<Guid> CreateAsync(string name, string? color = null, CancellationToken ct = default)
    {
        double position;
        await using (var db = await dbFactory.CreateDbContextAsync(ct))
            position = (await db.Contexts.Where(c => c.DeletedAt == null).MaxAsync(c => (double?)c.Position, ct) ?? 0) + 1;

        var id = Guid.CreateVersion7();
        var now = SyncTime.Now(time);
        await sync.PushAsync([new PushEnvelope(Context.TypeName, id, null, now, false, new JsonObject
        {
            ["name"] = name.Trim(),
            ["color"] = color,
            ["position"] = position,
            ["createdAt"] = SyncTime.Format(now),
        })], PushOrigin.Local, atomic: true, ct);
        return id;
    }

    public async Task UpdateAsync(Guid id, string? name = null, string? color = null, bool clearColor = false, double? position = null, CancellationToken ct = default)
    {
        var current = await GetAsync(id, ct) ?? throw new InvalidOperationException("Context does not exist.");
        var data = new JsonObject();
        if (name is not null) data["name"] = name.Trim();
        if (color is not null || clearColor) data["color"] = color;
        if (position is not null) data["position"] = position;
        await sync.PushAsync([new PushEnvelope(Context.TypeName, id, current.Version, SyncTime.Now(time), false, data)], PushOrigin.Local, atomic: true, ct);
    }

    /// <summary>Deletes a context. Only allowed when it is empty and not the default context.</summary>
    public async Task<bool> TryDeleteAsync(Guid id, CancellationToken ct = default)
    {
        var current = await GetAsync(id, ct);
        if (current is null) return false;
        var response = await sync.PushAsync([new PushEnvelope(Context.TypeName, id, current.Version, SyncTime.Now(time), true, null)], PushOrigin.Local, ct: ct);
        return response.Results[0].Status == PushStatus.Accepted;
    }

    public async Task<bool> IsEmptyAsync(Guid id, CancellationToken ct = default) =>
        (await actions.ListInContextAsync(id, ct)).Count == 0;

    /// <summary>"Move everything to …": moves all entities of all modules from one context to another.</summary>
    public async Task MoveAllAsync(Guid from, Guid to, CancellationToken ct = default) =>
        await actions.MoveToContextAsync(await actions.ListInContextAsync(from, ct), to, ct);
}
