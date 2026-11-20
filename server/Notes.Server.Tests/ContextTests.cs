using System.Text.Json.Nodes;
using Microsoft.EntityFrameworkCore;
using Notes.Core;
using Notes.Core.Capture;
using Notes.Core.Contexts;
using Notes.Core.Sync;
using Notes.Modules.Stickies;
using static Notes.Server.Tests.TestApp;

namespace Notes.Server.Tests;

public sealed class ContextTests
{
    private static readonly DateTime T0 = new(2026, 11, 20, 10, 0, 0, DateTimeKind.Utc);

    private static PushEnvelope NewDummy(Guid id, Guid contextId, string text) =>
        new(Dummy.TypeName, id, null, T0, false, new() { ["contextId"] = contextId.ToString(), ["text"] = text });

    [Fact]
    public async Task Default_context_exists_with_fixed_id()
    {
        await using var app = await CreateProductionAsync();

        var contexts = await app.Contexts.ListAsync();

        var privat = Assert.Single(contexts);
        Assert.Equal(Context.DefaultId, privat.Id);
        Assert.Equal("Privat", privat.Name);
        Assert.Contains((await app.PullAllAsync()), e => e.EntityType == Context.TypeName && e.Id == Context.DefaultId);
    }

    [Fact]
    public async Task Listing_filters_by_context_across_types()
    {
        await using var app = await CreateAsync();
        var work = await app.Contexts.CreateAsync("Arbeit");
        var s1 = await app.Actions.CaptureAsync(new CaptureContent(null, "privat"), Context.DefaultId);
        var s2 = await app.Actions.CaptureAsync(new CaptureContent(null, "arbeit"), work);
        var d1 = Guid.CreateVersion7();
        var d2 = Guid.CreateVersion7();
        await app.PushAsync(NewDummy(d1, Context.DefaultId, "privat"), NewDummy(d2, work, "arbeit"));

        var inWork = await app.Actions.ListInContextAsync(work);
        var inPrivate = await app.Actions.ListInContextAsync(Context.DefaultId);

        Assert.Equal([s2, new EntityRef(Dummy.TypeName, d2)], inWork.OrderBy(r => r.EntityType != Sticky.TypeName));
        Assert.Equal([s1, new EntityRef(Dummy.TypeName, d1)], inPrivate.OrderBy(r => r.EntityType != Sticky.TypeName));
        Assert.Equal([s2.Id], (await app.Stickies.ListAsync(work)).Select(s => s.Id));
    }

    [Fact]
    public async Task Move_to_context_works_for_every_type()
    {
        await using var app = await CreateAsync();
        var work = await app.Contexts.CreateAsync("Arbeit");
        var sticky = await app.Actions.CaptureAsync(new CaptureContent(null, "x"), Context.DefaultId);
        var dummy = Guid.CreateVersion7();
        await app.PushOneAsync(NewDummy(dummy, Context.DefaultId, "y"));

        await app.Actions.MoveToContextAsync([sticky, new EntityRef(Dummy.TypeName, dummy)], work);

        Assert.Equal(2, (await app.Actions.ListInContextAsync(work)).Count);
        Assert.Empty(await app.Actions.ListInContextAsync(Context.DefaultId));
        Assert.Equal("x", (await app.FindStickyAsync(sticky.Id))!.Content);
    }

    [Fact]
    public async Task Convert_keeps_the_source_context()
    {
        await using var app = await CreateAsync();
        var work = await app.Contexts.CreateAsync("Arbeit");
        var sticky = await app.Actions.CaptureAsync(new CaptureContent(null, "x"), work);

        var dummy = await app.Actions.ConvertAsync(sticky, "dummy", deleteSource: true);

        Assert.Equal([dummy], await app.Actions.ListInContextAsync(work));
    }

    [Fact]
    public async Task Context_can_only_be_deleted_when_empty()
    {
        await using var app = await CreateAsync();
        var work = await app.Contexts.CreateAsync("Arbeit");
        await app.Actions.CaptureAsync(new CaptureContent(null, "x"), work);
        await app.PushOneAsync(NewDummy(Guid.CreateVersion7(), work, "y"));

        Assert.False(await app.Contexts.TryDeleteAsync(work));
        Assert.NotNull(await app.Contexts.GetAsync(work));

        await app.Contexts.MoveAllAsync(work, Context.DefaultId);
        Assert.True(await app.Contexts.IsEmptyAsync(work));
        Assert.True(await app.Contexts.TryDeleteAsync(work));
        Assert.Null(await app.Contexts.GetAsync(work));
        Assert.Equal(2, (await app.Actions.ListInContextAsync(Context.DefaultId)).Count);
    }

    [Fact]
    public async Task Context_deletion_via_push_is_refused_when_not_empty()
    {
        await using var app = await CreateAsync();
        var work = await app.Contexts.CreateAsync("Arbeit");
        await app.Actions.CaptureAsync(new CaptureContent(null, "x"), work);
        var ctx = (await app.Contexts.GetAsync(work))!;

        var r = await app.PushOneAsync(Delete(Context.TypeName, work, ctx.Version, T0.AddDays(1)));

        Assert.Equal(PushStatus.Conflict, r.Status);
        Assert.Equal(ConflictReasons.ContextNotEmpty, r.Reason);
        Assert.False(r.Server!.Deleted);
    }

    [Fact]
    public async Task Default_context_cannot_be_deleted_but_renamed()
    {
        await using var app = await CreateProductionAsync();

        Assert.False(await app.Contexts.TryDeleteAsync(Context.DefaultId));
        await app.Contexts.UpdateAsync(Context.DefaultId, name: "Zuhause");

        Assert.Equal("Zuhause", (await app.Contexts.GetAsync(Context.DefaultId))!.Name);
    }

    [Fact]
    public async Task Offline_created_context_and_content_arrive_in_one_push()
    {
        await using var app = await CreateAsync();
        var ctx = Guid.CreateVersion7();
        var sticky = Guid.CreateVersion7();
        var dummy = Guid.CreateVersion7();

        // Order inside the push does not matter: core types are applied first.
        var response = await app.PushAsync(
            NewSticky(sticky, T0, "x", contextId: ctx),
            NewDummy(dummy, ctx, "y"),
            new PushEnvelope(Context.TypeName, ctx, null, T0, false, new() { ["name"] = "Reise", ["position"] = 3 }));

        Assert.All(response.Results, r => Assert.Equal(PushStatus.Accepted, r.Status));
        Assert.Equal("Reise", (await app.Contexts.GetAsync(ctx))!.Name);
        Assert.Equal(2, (await app.Actions.ListInContextAsync(ctx)).Count);
    }

    [Fact]
    public async Task Move_all_and_delete_context_in_one_push()
    {
        await using var app = await CreateAsync();
        var work = await app.Contexts.CreateAsync("Arbeit");
        var s = await app.Actions.CaptureAsync(new CaptureContent(null, "x"), work);
        var sticky = (await app.FindStickyAsync(s.Id))!;
        var ctx = (await app.Contexts.GetAsync(work))!;

        var response = await app.PushAsync(
            Delete(Context.TypeName, work, ctx.Version, T0.AddDays(1)),
            Edit(Sticky.TypeName, s.Id, sticky.Version, T0.AddDays(1), new() { ["contextId"] = Context.DefaultId.ToString() }));

        Assert.All(response.Results, r => Assert.Equal(PushStatus.Accepted, r.Status));
        Assert.Null(await app.Contexts.GetAsync(work));
    }

    [Fact]
    public async Task Entity_with_never_existing_context_is_rejected()
    {
        await using var app = await CreateAsync();

        var r = await app.PushOneAsync(NewSticky(Guid.CreateVersion7(), T0, "x", contextId: Guid.CreateVersion7()));

        Assert.Equal(PushStatus.Rejected, r.Status);
        Assert.Equal(RejectReasons.UnknownContext, r.Reason);
    }

    [Fact]
    public async Task Pushing_into_a_deleted_context_restores_it()
    {
        await using var app = await CreateAsync();
        var work = await app.Contexts.CreateAsync("Arbeit");
        Assert.True(await app.Contexts.TryDeleteAsync(work));

        // A device that was offline still had "Arbeit" active and captured something.
        var r = await app.PushOneAsync(NewSticky(Guid.CreateVersion7(), T0.AddDays(1), "x", contextId: work));

        Assert.Equal(PushStatus.Accepted, r.Status);
        Assert.NotNull(await app.Contexts.GetAsync(work));
        Assert.Contains(await app.PullAllAsync(), e => e.Id == work && !e.Deleted);
    }

    [Fact]
    public async Task Context_conflicts_are_last_write_wins_without_copy()
    {
        await using var app = await CreateAsync();
        var v = (await app.Contexts.GetAsync(Context.DefaultId))!.Version;
        await app.PushOneAsync(Edit(Context.TypeName, Context.DefaultId, v, T0.AddMinutes(1), new() { ["name"] = "A" }));

        var r = await app.PushOneAsync(Edit(Context.TypeName, Context.DefaultId, v, T0.AddMinutes(2), new JsonObject { ["name"] = "B" }));

        Assert.Equal(ConflictWinner.Client, r.Winner);
        Assert.Null(r.ConflictCopy);
        Assert.Single(await app.Contexts.ListAsync());
        Assert.Equal("B", (await app.Contexts.GetAsync(Context.DefaultId))!.Name);
    }
}
