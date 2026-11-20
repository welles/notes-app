using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Notes.Core.Sync;

namespace Notes.Modules.Stickies;

/// <summary>A post-it. Entity type <c>stickies.sticky</c>.</summary>
[SyncEntityType(TypeName)]
public sealed class Sticky : ContextScopedEntity
{
    public const string TypeName = "stickies.sticky";

    public string? Title { get; set; }

    /// <summary>Plaintext, line breaks preserved. No markdown in v1.</summary>
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

    public static readonly IReadOnlyList<string> All = ["yellow", "green", "blue", "pink", "purple", "orange", "gray"];

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
