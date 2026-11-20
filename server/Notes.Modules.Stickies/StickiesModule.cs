using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Notes.Core.Capture;
using Notes.Core.Modules;
using Notes.Core.Sync;

namespace Notes.Modules.Stickies;

public sealed class StickiesModule : IModule
{
    public const string ModuleKey = "stickies";

    public string Key => ModuleKey;
    public string DisplayName => "Post-its";

    public void ConfigureServices(IServiceCollection services)
    {
        services.AddSingleton<ISyncHandler, StickySyncHandler>();
        services.AddSingleton<ICaptureTarget, StickyCaptureTarget>();
        services.AddSingleton<StickyService>();
    }

    public void ConfigureModel(ModelBuilder modelBuilder) => modelBuilder.ApplyConfiguration(new StickyConfiguration());
}
