using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;
using Notes.Core;
using Notes.Core.Data;
using Notes.Core.Modules;
using Notes.Modules.Stickies;

namespace Notes.Server;

/// <summary>Composition root: the one place that lists the modules of this host.</summary>
public static class NotesComposition
{
    public const string DefaultConnectionString = "Data Source=/data/notes.db";

    public static IModule[] Modules() => [new StickiesModule()];

    public static IServiceCollection AddNotes(this IServiceCollection services) =>
        services.AddNotesCore(
            (sp, o) => ConfigureDb(o, sp.GetRequiredService<IConfiguration>().GetConnectionString("Default") ?? DefaultConnectionString),
            Modules());

    public static void ConfigureDb(DbContextOptionsBuilder o, string connectionString) =>
        o.UseSqlite(connectionString, s => s.MigrationsAssembly(typeof(NotesComposition).Assembly.GetName().Name));
}

/// <summary>Used by <c>dotnet ef</c> to create migrations.</summary>
internal sealed class DesignTimeDbContextFactory : IDesignTimeDbContextFactory<NotesDbContext>
{
    public NotesDbContext CreateDbContext(string[] args)
    {
        var o = new DbContextOptionsBuilder<NotesDbContext>();
        NotesComposition.ConfigureDb(o, "Data Source=design.db");
        o.ReplaceService<Microsoft.EntityFrameworkCore.Infrastructure.IModelCacheKeyFactory, ModuleModelCacheKeyFactory>();
        return new NotesDbContext(o.Options, new ModuleSet(NotesComposition.Modules()));
    }
}
