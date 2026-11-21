using System.Text.Json.Nodes;
using Notes.Core.Capture;
using Notes.Core.Sync;

namespace Notes.Modules.Stickies;

public sealed class StickySyncHandler : SyncHandler<Sticky>
{
    public const string ConflictPrefix = "Konflikt: ";

    public override Type DataSchema => typeof(StickyData);

    protected override IReadOnlyCollection<string> Fields { get; } = ["title", "content", "color", "pinned", "position"];

    protected override void Write(Sticky e, JsonObject data)
    {
        data["title"] = e.Title;
        data["content"] = e.Content;
        data["color"] = e.Color;
        data["pinned"] = e.Pinned;
        data["position"] = e.Position;
    }

    protected override Action<Sticky> Parse(JsonObject data)
    {
        var hasTitle = JsonFields.Has(data, "title", out var t);
        var title = hasTitle ? JsonFields.String(t, "title") : null;
        var hasContent = JsonFields.Has(data, "content", out var c);
        var content = hasContent ? JsonFields.String(c, "content") ?? "" : "";
        var hasColor = JsonFields.Has(data, "color", out var col);
        var color = hasColor ? JsonFields.String(col, "color") ?? StickyColors.Default : StickyColors.Default;
        var hasPinned = JsonFields.Has(data, "pinned", out var pin);
        var pinned = hasPinned && pin is not null && JsonFields.Bool(pin, "pinned");
        var hasPos = JsonFields.Has(data, "position", out var pos);
        var position = hasPos && pos is not null ? JsonFields.Double(pos, "position") : 0;

        return e =>
        {
            if (hasTitle) e.Title = string.IsNullOrEmpty(title) ? null : title;
            if (hasContent) e.Content = content;
            if (hasColor) e.Color = color;
            if (hasPinned) e.Pinned = pinned;
            if (hasPos) e.Position = position;
        };
    }

    /// <summary>A copy is only worth it if the text differs; colour/pin/position conflicts are plain LWW.</summary>
    protected override bool NeedsConflictCopy(Sticky winner, Sticky loser) =>
        (winner.Title ?? "") != (loser.Title ?? "") || winner.Content != loser.Content;

    /// <summary>
    /// Legacy post-its with a title keep it there (older clients show it), prefixed; all others get the mark in
    /// their first heading, so it is visible on every client version.
    /// </summary>
    protected override void PrepareConflictCopy(Sticky copy)
    {
        if (!string.IsNullOrEmpty(copy.Title)) copy.Title = ConflictPrefix + copy.Title;
        else copy.Content = StickyText.MarkConflict(copy.Content);
    }

    /// <summary>A leading heading becomes the title for modules that have one.</summary>
    protected override CaptureContent Export(Sticky e)
    {
        if (!string.IsNullOrEmpty(e.Title)) return new(e.Title, e.Content);
        var (title, text) = StickyText.SplitHeading(e.Content);
        return new(title, text);
    }
}
