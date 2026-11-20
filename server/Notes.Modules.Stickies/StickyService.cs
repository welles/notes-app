using System.Text.Json.Nodes;
using Microsoft.EntityFrameworkCore;
using Notes.Core.Capture;
using Notes.Core.Data;
using Notes.Core.Sync;

namespace Notes.Modules.Stickies;

/// <summary>Changes to a post-it. All fields are optional; only set ones are sent (merge semantics).</summary>
public sealed record StickyPatch(string? Title = null, bool ClearTitle = false, string? Content = null, string? Color = null, bool? Pinned = null);

/// <summary>
/// Post-it operations for the web UI. Every write is a push through the generic <see cref="SyncService"/>,
/// so the web uses exactly the same versioning and conflict rules as the clients.
/// </summary>
public sealed class StickyService(
    IDbContextFactory<NotesDbContext> dbFactory,
    SyncService sync,
    EntityActions actions,
    TimeProvider time)
{
    private const double MinGap = 1e-6;

    /// <summary>Post-its of a context: pinned first, then by position.</summary>
    public async Task<IReadOnlyList<Sticky>> ListAsync(Guid contextId, CancellationToken ct = default)
    {
        await using var db = await dbFactory.CreateDbContextAsync(ct);
        return await Ordered(db, contextId).AsNoTracking().ToListAsync(ct);
    }

    public async Task<Sticky?> GetAsync(Guid id, CancellationToken ct = default)
    {
        await using var db = await dbFactory.CreateDbContextAsync(ct);
        return await db.Set<Sticky>().AsNoTracking().FirstOrDefaultAsync(s => s.Id == id, ct);
    }

    public async Task<Guid> CreateAsync(Guid contextId, string? title, string content, string? color = null, CancellationToken ct = default)
    {
        var reference = await actions.CaptureAsync(new CaptureContent(title, content), contextId, StickiesModule.ModuleKey, ct);
        if (color is not null && color != StickyColors.Default)
        {
            var created = await GetAsync(reference.Id, ct);
            await PushAsync(reference.Id, created!.Version, new JsonObject { ["color"] = color }, ct);
        }
        return reference.Id;
    }

    /// <summary>Applies a patch. <paramref name="baseVersion"/> is the version the UI showed; a mismatch is resolved like a sync conflict.</summary>
    public async Task<PushEntryResult> UpdateAsync(Guid id, long baseVersion, StickyPatch patch, CancellationToken ct = default)
    {
        var data = new JsonObject();
        if (patch.Title is not null || patch.ClearTitle) data["title"] = patch.ClearTitle ? null : patch.Title;
        if (patch.Content is not null) data["content"] = patch.Content;
        if (patch.Color is not null) data["color"] = patch.Color;
        if (patch.Pinned is not null) data["pinned"] = patch.Pinned;
        return await PushAsync(id, baseVersion, data, ct);
    }

    /// <summary>Deletes as tombstone (can be undone with <see cref="RestoreAsync"/>).</summary>
    public async Task<PushEntryResult> DeleteAsync(Guid id, long baseVersion, CancellationToken ct = default) =>
        (await sync.PushAsync([new PushEnvelope(Sticky.TypeName, id, baseVersion, SyncTime.Now(time), true, null)], PushOrigin.Local, ct: ct)).Results[0];

    public async Task<PushEntryResult> RestoreAsync(Guid id, CancellationToken ct = default)
    {
        var current = await GetAsync(id, ct) ?? throw new InvalidOperationException("Post-it does not exist.");
        return await PushAsync(id, current.Version, [], ct);
    }

    /// <summary>
    /// Moves a post-it between two neighbours (either may be null at the start/end of the list).
    /// Renormalises all positions of the context when the gap becomes too small – one push either way.
    /// </summary>
    public async Task ReorderAsync(Guid id, Guid? after, Guid? before, CancellationToken ct = default)
    {
        await using var db = await dbFactory.CreateDbContextAsync(ct);
        var sticky = await db.Set<Sticky>().AsNoTracking().FirstOrDefaultAsync(s => s.Id == id, ct)
            ?? throw new InvalidOperationException("Post-it does not exist.");
        var list = await db.Set<Sticky>().AsNoTracking()
            .Where(s => s.ContextId == sticky.ContextId && s.DeletedAt == null && s.Id != id)
            .OrderBy(s => s.Position).ToListAsync(ct);
        var a = after is null ? null : list.FirstOrDefault(s => s.Id == after);
        var b = before is null ? null : list.FirstOrDefault(s => s.Id == before);

        double position = (a, b) switch
        {
            (null, null) => 0,
            ({ } x, null) => x.Position + 1,
            (null, { } y) => y.Position - 1,
            ({ } x, { } y) => (x.Position + y.Position) / 2,
        };
        var now = SyncTime.Now(time);

        if (a is not null && b is not null && Math.Abs(b.Position - a.Position) < MinGap)
        {
            var insertAt = list.IndexOf(a) + 1;
            list.Insert(insertAt, sticky);
            var entries = list.Select((s, i) => (s, pos: (double)i))
                .Where(x => x.s.Position != x.pos || x.s.Id == id)
                .Select(x => new PushEnvelope(Sticky.TypeName, x.s.Id, x.s.Version, now, false, new JsonObject { ["position"] = x.pos }))
                .ToList();
            await sync.PushAsync(entries, PushOrigin.Local, ct: ct);
            return;
        }

        await sync.PushAsync([new PushEnvelope(Sticky.TypeName, id, sticky.Version, now, false, new JsonObject { ["position"] = position })], PushOrigin.Local, ct: ct);
    }

    internal static async Task<double> TopPositionAsync(NotesDbContext db, Guid contextId, CancellationToken ct) =>
        (await db.Set<Sticky>().Where(s => s.ContextId == contextId && s.DeletedAt == null).MinAsync(s => (double?)s.Position, ct) ?? 1) - 1;

    private static IQueryable<Sticky> Ordered(NotesDbContext db, Guid contextId) =>
        db.Set<Sticky>().Where(s => s.ContextId == contextId && s.DeletedAt == null)
            .OrderByDescending(s => s.Pinned).ThenBy(s => s.Position);

    private async Task<PushEntryResult> PushAsync(Guid id, long baseVersion, JsonObject data, CancellationToken ct) =>
        (await sync.PushAsync([new PushEnvelope(Sticky.TypeName, id, baseVersion, SyncTime.Now(time), false, data)], PushOrigin.Local, ct: ct)).Results[0];
}
