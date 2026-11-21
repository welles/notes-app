using Microsoft.EntityFrameworkCore;
using Notes.Core;
using Notes.Core.Capture;
using Notes.Core.Contexts;
using Notes.Core.Sync;
using Notes.Modules.Stickies;
using static Notes.Server.Tests.TestApp;

namespace Notes.Server.Tests;

/// <summary>A second, test-only entity type proves that sync, capture and "convert to …" know no concrete module.</summary>
public sealed class GenericModuleTests
{
    private static readonly DateTime T0 = new(2026, 11, 20, 10, 0, 0, DateTimeKind.Utc);

    [Fact]
    public async Task Dummy_type_syncs_through_the_same_protocol()
    {
        await using var app = await CreateAsync();
        var dummy = Guid.CreateVersion7();
        var sticky = Guid.CreateVersion7();

        var response = await app.PushAsync(
            new PushEnvelope(Dummy.TypeName, dummy, null, T0, false, new() { ["contextId"] = Context.DefaultId.ToString(), ["text"] = "hello" }),
            NewSticky(sticky, T0, "post-it"));
        Assert.All(response.Results, r => Assert.Equal(PushStatus.Accepted, r.Status));

        var v = response.Results[0].Server!.Version;
        await app.PushOneAsync(Edit(Dummy.TypeName, dummy, v, T0.AddMinutes(5), new() { ["text"] = "server" }));
        var conflict = await app.PushOneAsync(Edit(Dummy.TypeName, dummy, v, T0.AddMinutes(1), new() { ["text"] = "client" }));
        Assert.Equal(ConflictWinner.Server, conflict.Winner);
        Assert.NotNull(conflict.ConflictCopy);
        Assert.Equal(Dummy.TypeName, conflict.ConflictCopy!.EntityType);

        var pulled = await app.PullAllAsync();
        Assert.Contains(pulled, e => e.EntityType == Dummy.TypeName && e.Id == dummy && e.Data["text"]!.GetValue<string>() == "server");
        Assert.Contains(pulled, e => e.EntityType == Dummy.TypeName && e.Data["text"]!.GetValue<string>() == "[conflict] client");
        Assert.Contains(pulled, e => e.EntityType == Sticky.TypeName && e.Id == sticky);
    }

    [Fact]
    public async Task Conversion_menu_appears_only_with_more_than_one_target()
    {
        await using var production = await CreateProductionAsync();
        Assert.False(production.Targets.ConversionAvailable);
        Assert.Equal(StickiesModule.ModuleKey, production.Targets.Default.Key);

        await using var app = await CreateAsync();
        Assert.True(app.Targets.ConversionAvailable);
    }

    [Fact]
    public async Task Capture_creates_sticky_in_given_context()
    {
        await using var app = await CreateAsync();

        var reference = await app.Actions.CaptureAsync(new CaptureContent("Idee", "Zeile 1\nZeile 2"), Context.DefaultId);

        Assert.Equal(Sticky.TypeName, reference.EntityType);
        var sticky = (await app.FindStickyAsync(reference.Id))!;
        Assert.Equal((null, "# Idee\n\nZeile 1\nZeile 2", Context.DefaultId), (sticky.Title, sticky.Content, sticky.ContextId));
    }

    [Fact]
    public async Task Convert_sticky_to_dummy_and_delete_source_atomically()
    {
        await using var app = await CreateAsync();
        var source = await app.Actions.CaptureAsync(new CaptureContent("Titel", "Text"), Context.DefaultId);

        var target = await app.Actions.ConvertAsync(source, "dummy", deleteSource: true);

        Assert.Equal(Dummy.TypeName, target.EntityType);
        Assert.NotNull((await app.FindStickyAsync(source.Id))!.DeletedAt);
        await using var db = await app.Db.CreateDbContextAsync();
        var dummy = await db.Set<Dummy>().SingleAsync(d => d.Id == target.Id);
        Assert.Equal("Titel: Text", dummy.Text);
    }

    [Fact]
    public async Task Convert_can_keep_the_source()
    {
        await using var app = await CreateAsync();
        var source = await app.Actions.CaptureAsync(new CaptureContent(null, "Text"), Context.DefaultId);

        await app.Actions.ConvertAsync(source, "dummy", deleteSource: false);

        Assert.Null((await app.FindStickyAsync(source.Id))!.DeletedAt);
    }

    [Fact]
    public async Task Failed_conversion_changes_nothing()
    {
        await using var app = await CreateAsync();
        var source = await app.Actions.CaptureAsync(new CaptureContent(null, DummyCaptureTarget.FailText), Context.DefaultId);
        var versionBefore = (await app.Sync.PullAsync(0, 500)).Cursor;

        await Assert.ThrowsAsync<SyncRejectedException>(() => app.Actions.ConvertAsync(source, "dummy", deleteSource: true));

        Assert.Null((await app.FindStickyAsync(source.Id))!.DeletedAt);
        await using var db = await app.Db.CreateDbContextAsync();
        Assert.Empty(await db.Set<Dummy>().ToListAsync());
        Assert.Equal(versionBefore, (await app.Sync.PullAsync(0, 500)).Cursor);
    }

    [Fact]
    public async Task Convert_back_and_forth_uses_neutral_content()
    {
        await using var app = await CreateAsync();
        var dummyId = Guid.CreateVersion7();
        await app.PushOneAsync(new PushEnvelope(Dummy.TypeName, dummyId, null, T0, false,
            new() { ["contextId"] = Context.DefaultId.ToString(), ["text"] = "from dummy" }));

        var sticky = await app.Actions.ConvertAsync(new EntityRef(Dummy.TypeName, dummyId), StickiesModule.ModuleKey, deleteSource: true);

        Assert.Equal("from dummy", (await app.FindStickyAsync(sticky.Id))!.Content);
    }
}
