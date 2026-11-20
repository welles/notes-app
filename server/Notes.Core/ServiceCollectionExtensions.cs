using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Notes.Core.Capture;
using Notes.Core.Contexts;
using Notes.Core.Data;
using Notes.Core.Events;
using Notes.Core.Modules;
using Notes.Core.Sync;

namespace Notes.Core;

public static class ServiceCollectionExtensions
{
    /// <summary>Registers the core (sync, contexts, capture, events) and composes the given modules.</summary>
    public static IServiceCollection AddNotesCore(this IServiceCollection services, Action<IServiceProvider, DbContextOptionsBuilder> configureDb, params IModule[] modules)
    {
        services.AddSingleton(new ModuleSet(modules));
        services.AddDbContextFactory<NotesDbContext>((sp, o) =>
        {
            configureDb(sp, o);
            o.ReplaceService<IModelCacheKeyFactory, ModuleModelCacheKeyFactory>();
        });
        services.TryAddSingleton(TimeProvider.System);
        services.AddSingleton<EventBus>();
        services.AddSingleton<SyncHandlerRegistry>();
        services.AddSingleton<CaptureTargetRegistry>();
        services.AddSingleton<SyncService>();
        services.AddSingleton<EntityActions>();
        services.AddSingleton<ContextService>();
        services.AddSingleton<ISyncHandler, ContextSyncHandler>();

        foreach (var module in modules) module.ConfigureServices(services);
        return services;
    }
}
