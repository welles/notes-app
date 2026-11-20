using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Notes.Core.Modules;

/// <summary>
/// The single place where a module plugs into the host: services (incl. sync handlers and capture targets)
/// and its entities in the shared DbContext. A module only depends on Notes.Core, never on another module.
/// </summary>
public interface IModule
{
    /// <summary>Short technical key, also used as table prefix and API route segment (e.g. <c>stickies</c>).</summary>
    string Key { get; }

    string DisplayName { get; }

    void ConfigureServices(IServiceCollection services);

    /// <summary>Adds the module's entities (typically via <c>ApplyConfiguration</c>).</summary>
    void ConfigureModel(ModelBuilder modelBuilder);
}

/// <summary>The set of modules composed into this host.</summary>
public sealed class ModuleSet(IEnumerable<IModule> modules)
{
    public IReadOnlyList<IModule> Modules { get; } = modules.ToList();

    /// <summary>Identifies the EF model (different module sets, e.g. in tests, need different models).</summary>
    public string ModelKey => string.Join(",", Modules.Select(m => m.GetType().FullName));
}
