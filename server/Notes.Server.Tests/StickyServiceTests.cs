using Microsoft.EntityFrameworkCore;
using Notes.Core.Contexts;
using Notes.Core.Data;
using Notes.Core.Sync;
using Notes.Modules.Stickies;

namespace Notes.Server.Tests;

public sealed class StickyServiceTests
{
    [Fact]
    public async Task New_stickies_go_to_the_top_and_pinned_ones_come_first()
    {
        await using var app = await TestApp.CreateProductionAsync();
        var a = await app.Stickies.CreateAsync(Context.DefaultId, "a");
        var b = await app.Stickies.CreateAsync(Context.DefaultId, "b");
        var c = await app.Stickies.CreateAsync(Context.DefaultId, "# Titel\n\nc", "blue");

        var bSticky = (await app.Stickies.GetAsync(b))!;
        await app.Stickies.UpdateAsync(b, bSticky.Version, new StickyPatch(Pinned: true));

        var list = await app.Stickies.ListAsync(Context.DefaultId);
        Assert.Equal([b, c, a], list.Select(s => s.Id));
        Assert.Equal("blue", list[1].Color);
    }

    [Fact]
    public async Task Rapid_local_edits_are_not_mistaken_for_duplicates()
    {
        await using var app = await TestApp.CreateProductionAsync();
        var id = await app.Stickies.CreateAsync(Context.DefaultId, "a");

        // Clock does not advance between edits.
        for (var i = 0; i < 3; i++)
        {
            var s = (await app.Stickies.GetAsync(id))!;
            var r = await app.Stickies.UpdateAsync(id, s.Version, new StickyPatch(Content: $"edit {i}"));
            Assert.Equal(PushStatus.Accepted, r.Status);
        }

        Assert.Equal("edit 2", (await app.Stickies.GetAsync(id))!.Content);
    }

    [Fact]
    public async Task Stale_web_edit_is_resolved_like_a_sync_conflict()
    {
        await using var app = await TestApp.CreateProductionAsync();
        var id = await app.Stickies.CreateAsync(Context.DefaultId, "a");
        var v = (await app.Stickies.GetAsync(id))!.Version;
        await app.Stickies.UpdateAsync(id, v, new StickyPatch(Content: "first tab"));
        app.Time.Advance(TimeSpan.FromSeconds(5));

        var r = await app.Stickies.UpdateAsync(id, v, new StickyPatch(Content: "second tab"));

        Assert.Equal(PushStatus.Conflict, r.Status);
        Assert.Equal("second tab", (await app.Stickies.GetAsync(id))!.Content);
        Assert.Contains(await app.Stickies.ListAsync(Context.DefaultId), s => s.Content == "# Konflikt\n\nfirst tab" && s.Title == null);
    }

    [Fact]
    public async Task Delete_and_undo()
    {
        await using var app = await TestApp.CreateProductionAsync();
        var id = await app.Stickies.CreateAsync(Context.DefaultId, "# t\n\na");

        await app.Stickies.DeleteAsync(id, (await app.Stickies.GetAsync(id))!.Version);
        Assert.Empty(await app.Stickies.ListAsync(Context.DefaultId));

        await app.Stickies.RestoreAsync(id);
        var restored = Assert.Single(await app.Stickies.ListAsync(Context.DefaultId));
        Assert.Equal("# t\n\na", restored.Content);
    }

    [Fact]
    public async Task Reorder_uses_midpoint_and_renormalises_when_gap_is_tiny()
    {
        await using var app = await TestApp.CreateProductionAsync();
        var c = await app.Stickies.CreateAsync(Context.DefaultId, "c");
        var b = await app.Stickies.CreateAsync(Context.DefaultId, "b");
        var a = await app.Stickies.CreateAsync(Context.DefaultId, "a");
        Assert.Equal([a, b, c], (await app.Stickies.ListAsync(Context.DefaultId)).Select(s => s.Id));

        await app.Stickies.ReorderAsync(c, after: a, before: b);
        Assert.Equal([a, c, b], (await app.Stickies.ListAsync(Context.DefaultId)).Select(s => s.Id));

        // Squeeze positions until the gap is too small, then the next move renormalises.
        for (var i = 0; i < 60; i++)
        {
            var order = (await app.Stickies.ListAsync(Context.DefaultId)).Select(s => s.Id).ToList();
            await app.Stickies.ReorderAsync(order[2], after: order[0], before: order[1]);
        }
        var list = await app.Stickies.ListAsync(Context.DefaultId);
        Assert.Equal(3, list.Select(s => s.Position).Distinct().Count());
        Assert.True(list.Zip(list.Skip(1)).All(p => p.First.Position < p.Second.Position));
    }

    [Fact]
    public async Task Every_change_updates_sync_changes_in_the_same_version()
    {
        await using var app = await TestApp.CreateProductionAsync();
        var id = await app.Stickies.CreateAsync(Context.DefaultId, "a");
        await app.Stickies.UpdateAsync(id, (await app.Stickies.GetAsync(id))!.Version, new StickyPatch(Color: "pink"));

        await using var db = await app.Db.CreateDbContextAsync();
        var sticky = await db.Set<Sticky>().SingleAsync();
        var change = await db.SyncChanges.SingleAsync(c => c.EntityId == id);
        var state = await db.SyncState.SingleAsync();
        Assert.Equal(sticky.Version, change.Version);
        Assert.Equal(state.LastVersion, sticky.Version);
    }
}
