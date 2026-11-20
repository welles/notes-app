using System.Text.Json.Nodes;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Notes.Core.Capture;
using Notes.Core.Data;
using Notes.Core.Modules;
using Notes.Core.Sync;

namespace Notes.Server.Tests;

/// <summary>Test-only second module. Knows nothing about Post-its; shows that sync and conversion are generic.</summary>
[SyncEntityType(TypeName)]
public sealed class Dummy : ContextScopedEntity
{
    public const string TypeName = "test.dummy";
    public string Text { get; set; } = "";
}

public sealed class DummyHandler : SyncHandler<Dummy>
{
    protected override IReadOnlyCollection<string> Fields { get; } = ["text"];
    protected override void Write(Dummy e, JsonObject data) => data["text"] = e.Text;

    protected override Action<Dummy> Parse(JsonObject data)
    {
        var has = JsonFields.Has(data, "text", out var n);
        var text = has ? JsonFields.String(n, "text") ?? "" : "";
        return e => { if (has) e.Text = text; };
    }

    protected override bool NeedsConflictCopy(Dummy winner, Dummy loser) => winner.Text != loser.Text;
    protected override void PrepareConflictCopy(Dummy copy) => copy.Text = "[conflict] " + copy.Text;
    protected override CaptureContent Export(Dummy e) => new(null, e.Text);
}

public sealed class DummyCaptureTarget : ICaptureTarget
{
    public const string FailText = "!fail!";
    public string Key => "dummy";
    public string DisplayName => "Dummy";
    public string EntityType => Dummy.TypeName;

    public Task<JsonObject> CreateDataAsync(NotesDbContext db, CaptureContent content, Guid contextId, CancellationToken ct) =>
        Task.FromResult(content.Text == FailText
            ? new JsonObject { ["text"] = 42 } // invalid on purpose -> rejected
            : new JsonObject { ["text"] = (content.Title is null ? "" : content.Title + ": ") + content.Text });
}

public sealed class DummyModule : IModule
{
    public string Key => "dummy";
    public string DisplayName => "Dummy";
    public ModuleNavigation Navigation { get; } = new("Dummy", "/dummy", "");

    public void ConfigureServices(IServiceCollection services)
    {
        services.AddSingleton<ISyncHandler, DummyHandler>();
        services.AddSingleton<ICaptureTarget, DummyCaptureTarget>();
    }

    public void ConfigureModel(ModelBuilder b) => b.Entity<Dummy>().ToTable("dummy_item");
}
