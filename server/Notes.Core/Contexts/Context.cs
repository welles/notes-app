using System.Text.Json.Nodes;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Notes.Core.Sync;

namespace Notes.Core.Contexts;

/// <summary>A context like "Arbeit" or "Privat". Separates content across all modules. Core entity type <c>core.context</c>.</summary>
[SyncEntityType(TypeName)]
public sealed class Context : SyncEntity
{
    public const string TypeName = "core.context";

    /// <summary>Fixed, well-known id of the default context "Privat". Clients know it too, so offline first starts never create duplicates.</summary>
    public static readonly Guid DefaultId = Guid.Parse("019a0000-0000-7000-8000-000000000001");

    public string Name { get; set; } = "";

    /// <summary>Optional accent colour. Stored as string so values from newer versions survive.</summary>
    public string? Color { get; set; }

    public double Position { get; set; }
}

internal sealed class ContextConfiguration : IEntityTypeConfiguration<Context>
{
    public void Configure(EntityTypeBuilder<Context> e)
    {
        e.ToTable("core_context");
        var seeded = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        e.HasData(new Context
        {
            Id = Context.DefaultId,
            Name = "Privat",
            Position = 0,
            CreatedAt = seeded,
            ModifiedAt = seeded,
            Version = 1,
        });
    }
}

public sealed class ContextSyncHandler : SyncHandler<Context>
{
    public override int ApplyOrder => 0;

    protected override IReadOnlyCollection<string> Fields { get; } = ["name", "color", "position"];

    protected override void Write(Context e, JsonObject data)
    {
        data["name"] = e.Name;
        data["color"] = e.Color;
        data["position"] = e.Position;
    }

    protected override Action<Context> Parse(JsonObject data)
    {
        var hasName = JsonFields.Has(data, "name", out var n);
        var name = hasName ? JsonFields.String(n, "name") ?? "" : null;
        var hasColor = JsonFields.Has(data, "color", out var c);
        var color = hasColor ? JsonFields.String(c, "color") : null;
        var hasPos = JsonFields.Has(data, "position", out var p);
        var pos = hasPos && p is not null ? JsonFields.Double(p, "position") : 0;
        return e =>
        {
            if (hasName) e.Name = name!;
            if (hasColor) e.Color = color;
            if (hasPos) e.Position = pos;
        };
    }
}
