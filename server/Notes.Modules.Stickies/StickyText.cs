namespace Notes.Modules.Stickies;

/// <summary>
/// Post-its have no title of their own any more, only Markdown text. The <c>title</c> field stays in the
/// protocol for older clients: where one is set, it is shown as a heading above the text, and the next edit
/// with a current client moves it into the text for good.
/// </summary>
public static class StickyText
{
    /// <summary>The text as shown and edited: a legacy title becomes a level-1 heading.</summary>
    public static string Combine(string? title, string content) =>
        string.IsNullOrWhiteSpace(title) ? content
        : content.Length == 0 ? "# " + title.Trim()
        : "# " + title.Trim() + "\n\n" + content;

    public static string Of(Sticky s) => Combine(s.Title, s.Content);

    /// <summary>
    /// Splits a leading level-1 heading off the text (for "convert to …", where other modules may have a title).
    /// Returns a null title when the text doesn't start with one.
    /// </summary>
    public static (string? Title, string Text) SplitHeading(string text)
    {
        var end = text.IndexOf('\n');
        var first = end < 0 ? text : text[..end];
        if (!first.StartsWith("# ") || first[2..].Trim().Length == 0) return (null, text);
        var rest = end < 0 ? "" : text[(end + 1)..].TrimStart('\n', '\r');
        return (first[2..].Trim(), rest);
    }

    /// <summary>Marks a conflict copy in its first heading (or adds one), so it stands out between the originals.</summary>
    public static string MarkConflict(string text)
    {
        var (title, rest) = SplitHeading(text);
        return title is null ? Combine("Konflikt", text) : Combine(StickySyncHandler.ConflictPrefix + title, rest);
    }
}
