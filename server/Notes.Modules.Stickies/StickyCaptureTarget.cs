using System.Text.Json.Nodes;
using Microsoft.EntityFrameworkCore;
using Notes.Core.Capture;
using Notes.Core.Data;

namespace Notes.Modules.Stickies;

/// <summary>Creates a post-it from captured text. New post-its go to the top of the list.</summary>
public sealed class StickyCaptureTarget : ICaptureTarget
{
    public string Key => StickiesModule.ModuleKey;
    public string DisplayName => "Post-it";
    public string EntityType => Sticky.TypeName;

    public async Task<JsonObject> CreateDataAsync(NotesDbContext db, CaptureContent content, Guid contextId, CancellationToken ct) => new()
    {
        ["title"] = null,
        ["content"] = StickyText.Combine(content.Title, content.Text),
        ["color"] = StickyColors.Default,
        ["pinned"] = false,
        ["position"] = await StickyService.TopPositionAsync(db, contextId, ct),
    };
}
