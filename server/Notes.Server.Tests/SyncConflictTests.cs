using System.Text.Json.Nodes;
using Notes.Core.Sync;
using Notes.Modules.Stickies;
using static Notes.Server.Tests.TestApp;

namespace Notes.Server.Tests;

public sealed class SyncConflictTests
{
    private static readonly DateTime T0 = new(2026, 11, 20, 10, 0, 0, DateTimeKind.Utc);

    [Fact]
    public async Task New_entity_is_accepted_versioned_and_pulled()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();

        var r = await app.PushOneAsync(NewSticky(id, T0, "Milch kaufen"));

        Assert.Equal(PushStatus.Accepted, r.Status);
        Assert.True(r.Server!.Version > 1);
        Assert.Equal("Milch kaufen", r.Server.Data["content"]!.GetValue<string>());
        var pulled = await app.PullAllAsync();
        Assert.Contains(pulled, e => e.Id == id && e.Version == r.Server.Version && !e.Deleted);
    }

    [Fact]
    public async Task Update_with_matching_base_version_is_accepted()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var v1 = (await app.PushOneAsync(NewSticky(id, T0, "a"))).Server!.Version;

        var r = await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(1), new() { ["content"] = "b" }));

        Assert.Equal(PushStatus.Accepted, r.Status);
        Assert.True(r.Server!.Version > v1);
        Assert.Equal("b", (await app.FindStickyAsync(id))!.Content);
        Assert.Single(await app.AllStickiesAsync());
    }

    [Fact]
    public async Task Conflict_newer_client_wins_and_server_version_becomes_conflict_copy()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var v1 = (await app.PushOneAsync(NewSticky(id, T0, "original", "Titel"))).Server!.Version;
        await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(1), new() { ["content"] = "server edit" }));

        // Other device edited offline based on v1, later than the server edit.
        var r = await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(2), new() { ["content"] = "client edit" }));

        Assert.Equal(PushStatus.Conflict, r.Status);
        Assert.Equal(ConflictWinner.Client, r.Winner);
        Assert.Equal("client edit", (await app.FindStickyAsync(id))!.Content);
        var copy = await app.FindStickyAsync(r.ConflictCopy!.Id);
        Assert.Equal("server edit", copy!.Content);
        Assert.Equal("Konflikt: Titel", copy.Title);
    }

    [Fact]
    public async Task Conflict_newer_server_wins_and_client_version_becomes_conflict_copy()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var v1 = (await app.PushOneAsync(NewSticky(id, T0, "original"))).Server!.Version;
        await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(5), new() { ["content"] = "server edit" }));

        var r = await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(2), new() { ["content"] = "client edit" }));

        Assert.Equal(PushStatus.Conflict, r.Status);
        Assert.Equal(ConflictWinner.Server, r.Winner);
        Assert.Equal("server edit", r.Server!.Data["content"]!.GetValue<string>());
        var copy = await app.FindStickyAsync(r.ConflictCopy!.Id);
        Assert.Equal(("# Konflikt\n\nclient edit", null), (copy!.Content, copy.Title));
        Assert.Equal(2, (await app.AllStickiesAsync()).Count);
    }

    [Fact]
    public async Task Conflict_without_text_difference_is_plain_last_write_wins()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var v1 = (await app.PushOneAsync(NewSticky(id, T0, "same"))).Server!.Version;
        await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(1), new() { ["color"] = "blue" }));

        var r = await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(2), new() { ["color"] = "green" }));

        Assert.Equal(PushStatus.Conflict, r.Status);
        Assert.Null(r.ConflictCopy);
        Assert.Equal("green", (await app.FindStickyAsync(id))!.Color);
        Assert.Single(await app.AllStickiesAsync());
    }

    [Fact]
    public async Task Delete_against_edit_keeps_the_edit()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var v1 = (await app.PushOneAsync(NewSticky(id, T0, "a"))).Server!.Version;
        await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(1), new() { ["content"] = "edited" }));

        // Even a later delete loses against an edit the deleting device has not seen.
        var r = await app.PushOneAsync(Delete(Sticky.TypeName, id, v1, T0.AddMinutes(5)));

        Assert.Equal(PushStatus.Conflict, r.Status);
        Assert.Equal(ConflictWinner.Server, r.Winner);
        Assert.False(r.Server!.Deleted);
        var sticky = await app.FindStickyAsync(id);
        Assert.Null(sticky!.DeletedAt);
        Assert.Equal("edited", sticky.Content);
    }

    [Fact]
    public async Task Edit_against_delete_restores_the_entity()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var v1 = (await app.PushOneAsync(NewSticky(id, T0, "a"))).Server!.Version;
        await app.PushOneAsync(Delete(Sticky.TypeName, id, v1, T0.AddMinutes(5)));

        var r = await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(1), new() { ["content"] = "edited offline" }));

        Assert.Equal(PushStatus.Conflict, r.Status);
        Assert.Equal(ConflictWinner.Client, r.Winner);
        Assert.False(r.Server!.Deleted);
        Assert.Equal("edited offline", (await app.FindStickyAsync(id))!.Content);
    }

    [Fact]
    public async Task Delete_with_matching_version_creates_tombstone_that_is_pulled()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var v1 = (await app.PushOneAsync(NewSticky(id, T0, "a"))).Server!.Version;

        var r = await app.PushOneAsync(Delete(Sticky.TypeName, id, v1, T0.AddMinutes(1)));

        Assert.Equal(PushStatus.Accepted, r.Status);
        Assert.True(r.Server!.Deleted);
        Assert.NotNull((await app.FindStickyAsync(id))!.DeletedAt);
        Assert.Contains(await app.PullAllAsync(v1), e => e.Id == id && e.Deleted);
    }

    [Fact]
    public async Task Repeated_push_has_no_double_effect()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var create = NewSticky(id, T0, "a");
        var first = await app.PushOneAsync(create);

        var again = await app.PushOneAsync(create);

        Assert.Equal(PushStatus.Accepted, again.Status);
        Assert.Equal(first.Server!.Version, again.Server!.Version);
        Assert.Single(await app.AllStickiesAsync());
    }

    [Fact]
    public async Task Repeated_conflicting_push_creates_only_one_conflict_copy()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var v1 = (await app.PushOneAsync(NewSticky(id, T0, "a"))).Server!.Version;
        await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(5), new() { ["content"] = "server" }));
        var conflicting = Edit(Sticky.TypeName, id, v1, T0.AddMinutes(1), new() { ["content"] = "client" });

        var first = await app.PushOneAsync(conflicting);
        var versionAfterFirst = (await app.PullAllAsync()).Max(e => e.Version);
        var retry = await app.PushOneAsync(conflicting);

        Assert.NotNull(first.ConflictCopy);
        Assert.Equal(PushStatus.Conflict, retry.Status);
        Assert.Equal(2, (await app.AllStickiesAsync()).Count);
        Assert.Equal(versionAfterFirst, (await app.PullAllAsync()).Max(e => e.Version));
    }

    [Fact]
    public async Task Push_is_merge_missing_fields_stay_explicit_null_clears()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var v1 = (await app.PushOneAsync(NewSticky(id, T0, "text", "title"))).Server!.Version;

        var v2 = (await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(1), new() { ["pinned"] = true }))).Server!.Version;
        var s = (await app.FindStickyAsync(id))!;
        Assert.Equal(("title", "text", true), (s.Title, s.Content, s.Pinned));

        await app.PushOneAsync(Edit(Sticky.TypeName, id, v2, T0.AddMinutes(2), new() { ["title"] = null }));
        s = (await app.FindStickyAsync(id))!;
        Assert.Null(s.Title);
        Assert.Equal("text", s.Content);
    }

    [Fact]
    public async Task Unknown_fields_and_enum_values_are_preserved()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var create = NewSticky(id, T0, "a");
        create.Data!["color"] = "teal";
        create.Data["reminderAt"] = "2027-01-01T00:00:00.000Z";
        create.Data["future"] = new JsonObject { ["nested"] = new JsonArray(1, 2) };
        var v1 = (await app.PushOneAsync(create)).Server!.Version;

        // An older client edits only the content; unknown data must survive.
        var r = await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(1), new() { ["content"] = "b" }));

        var data = r.Server!.Data;
        Assert.Equal("teal", data["color"]!.GetValue<string>());
        Assert.Equal("2027-01-01T00:00:00.000Z", data["reminderAt"]!.GetValue<string>());
        Assert.Equal(2, data["future"]!["nested"]!.AsArray().Count);
        Assert.Equal("b", data["content"]!.GetValue<string>());
    }

    [Fact]
    public async Task Unknown_entity_type_is_rejected_rest_of_push_is_applied()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();

        var response = await app.PushAsync(
            new PushEnvelope("todos.todo", Guid.CreateVersion7(), null, T0, false, new() { ["text"] = "x" }),
            NewSticky(id, T0, "a"));

        Assert.Equal(PushStatus.Rejected, response.Results[0].Status);
        Assert.Equal(RejectReasons.UnknownType, response.Results[0].Reason);
        Assert.Equal(PushStatus.Accepted, response.Results[1].Status);
    }

    [Fact]
    public async Task Invalid_data_is_rejected_without_side_effects()
    {
        await using var app = await CreateAsync();
        var id = Guid.CreateVersion7();
        var v1 = (await app.PushOneAsync(NewSticky(id, T0, "a"))).Server!.Version;

        var r = await app.PushOneAsync(Edit(Sticky.TypeName, id, v1, T0.AddMinutes(1), new() { ["content"] = "b", ["pinned"] = "yes" }));

        Assert.Equal(PushStatus.Rejected, r.Status);
        Assert.StartsWith(RejectReasons.InvalidData, r.Reason);
        var s = (await app.FindStickyAsync(id))!;
        Assert.Equal(("a", v1), (s.Content, s.Version));
    }

    [Fact]
    public async Task Pull_pages_in_version_order()
    {
        await using var app = await CreateAsync();
        for (var i = 0; i < 7; i++) await app.PushOneAsync(NewSticky(Guid.CreateVersion7(), T0.AddSeconds(i), $"n{i}"));

        var versions = new List<long>();
        long cursor = 0;
        int pages = 0;
        while (true)
        {
            var page = await app.Sync.PullAsync(cursor, 3);
            pages++;
            versions.AddRange(page.Changes.Select(c => c.Version));
            cursor = page.Cursor;
            if (!page.HasMore) break;
        }

        Assert.Equal(3, pages); // 1 default context + 7 post-its = 8 changes
        Assert.Equal(8, versions.Count);
        Assert.Equal(versions.Order(), versions);
        Assert.Empty((await app.Sync.PullAsync(cursor, 3)).Changes);
    }

    [Fact]
    public async Task Purged_tombstones_require_full_resync_for_old_cursors()
    {
        await using var app = await CreateAsync();
        var keep = Guid.CreateVersion7();
        var gone = Guid.CreateVersion7();
        await app.PushOneAsync(NewSticky(keep, T0, "keep"));
        var cursorBeforeDelete = (await app.Sync.PullAsync(0, 500)).Cursor;
        var v = (await app.PushOneAsync(NewSticky(gone, T0, "gone"))).Server!.Version;
        await app.PushOneAsync(Delete(Sticky.TypeName, gone, v, app.Time.UtcNow));
        var currentCursor = (await app.Sync.PullAsync(0, 500)).Cursor;

        app.Time.Advance(TimeSpan.FromDays(91));
        Assert.Equal(1, await app.Sync.PurgeTombstonesAsync(TimeSpan.FromDays(90)));

        Assert.Null(await app.FindStickyAsync(gone));
        Assert.True((await app.Sync.PullAsync(cursorBeforeDelete, 500)).FullResyncRequired);
        Assert.False((await app.Sync.PullAsync(currentCursor, 500)).FullResyncRequired);
        var full = await app.Sync.PullAsync(0, 500);
        Assert.False(full.FullResyncRequired);
        Assert.DoesNotContain(full.Changes, c => c.Id == gone);
        Assert.Contains(full.Changes, c => c.Id == keep);
    }
}
