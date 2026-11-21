using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Notes.Core.Sync;

namespace Notes.Modules.Stickies;

/// <summary>A post-it. Entity type <c>stickies.sticky</c>.</summary>
[SyncEntityType(TypeName)]
public sealed class Sticky : ContextScopedEntity
{
    public const string TypeName = "stickies.sticky";

    /// <summary>Legacy: post-its no longer have a title. Shown as a heading above <see cref="Content"/> (<see cref="StickyText"/>).</summary>
    public string? Title { get; set; }

    /// <summary>Basic Markdown (headings, lists, emphasis, links), line breaks preserved.</summary>
    public string Content { get; set; } = "";

    /// <summary>One of <see cref="StickyColors"/>. Stored as string: unknown values from newer clients are kept unchanged.</summary>
    public string Color { get; set; } = StickyColors.Default;

    public bool Pinned { get; set; }

    /// <summary>Manual order (ascending). Inserting between two uses the midpoint; renormalised when the gap gets too small.</summary>
    public double Position { get; set; }
}

public static class StickyColors
{
    public const string Default = "yellow";

    public static IReadOnlyList<string> All => Notes.Core.Web.NoteColors.Names;

    /// <summary>Display fallback for unknown values (the stored value itself is never changed).</summary>
    public static string ForDisplay(string? color) => color is not null && All.Contains(color) ? color : Default;
}

internal sealed class StickyConfiguration : IEntityTypeConfiguration<Sticky>
{
    public void Configure(EntityTypeBuilder<Sticky> e)
    {
        e.ToTable("stickies_sticky");
        e.HasIndex(x => new { x.ContextId, x.DeletedAt });
    }
}

/// <summary><c>data</c> of <c>stickies.sticky</c> (API documentation).</summary>
[System.ComponentModel.Description("data of entity type stickies.sticky")]
public sealed record StickyData(
    Guid Id, DateTime CreatedAt, DateTime ModifiedAt, DateTime? DeletedAt, long Version,
    [property: System.ComponentModel.Description("Context the post-it belongs to.")] Guid ContextId,
    [property: System.ComponentModel.Description("Legacy, current clients write null. If set, show it as a level-1 heading above content; current clients move it into content on the next edit.")] string? Title,
    [property: System.ComponentModel.Description("Basic Markdown (headings, lists, checkboxes, bold, italic, strikethrough, code, links), line breaks preserved.")] string Content,
    [property: System.ComponentModel.Description("yellow, green, blue, pink, purple, orange, gray. Unknown values must be preserved and displayed as yellow.")] string Color,
    bool Pinned,
    [property: System.ComponentModel.Description("Ascending manual order.")] double Position) : SyncDataBase(Id, CreatedAt, ModifiedAt, DeletedAt, Version);
