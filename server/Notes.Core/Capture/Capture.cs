using System.Text.Json.Nodes;
using Notes.Core.Data;

namespace Notes.Core.Capture;

/// <summary>Module-neutral content: what every module can export and every capture target can create from.</summary>
public sealed record CaptureContent(string? Title, string Text, EntityRef? Source = null);

/// <summary>
/// A module that can create something from text registers a capture target. Used by quick capture and by "convert to …".
/// </summary>
public interface ICaptureTarget
{
    /// <summary>Stable key, e.g. <c>stickies</c>.</summary>
    string Key { get; }

    /// <summary>Shown in menus, e.g. "Post-it".</summary>
    string DisplayName { get; }

    /// <summary>Entity type that gets created.</summary>
    string EntityType { get; }

    /// <summary>Builds the <c>data</c> of the new entity (without <c>contextId</c>, which the core sets).</summary>
    Task<JsonObject> CreateDataAsync(NotesDbContext db, CaptureContent content, Guid contextId, CancellationToken ct);
}

public sealed class CaptureTargetRegistry(IEnumerable<ICaptureTarget> targets)
{
    public IReadOnlyList<ICaptureTarget> All { get; } = targets.ToList();

    /// <summary>Target used by quick capture when none is chosen. In v1 the only one: Post-its.</summary>
    public ICaptureTarget Default => All.Count > 0 ? All[0] : throw new InvalidOperationException("No capture target registered.");

    /// <summary>"Convert to …" is only offered when there is more than one target.</summary>
    public bool ConversionAvailable => All.Count > 1;

    public ICaptureTarget Get(string key) =>
        All.FirstOrDefault(t => t.Key == key) ?? throw new InvalidOperationException($"Unknown capture target '{key}'.");
}
