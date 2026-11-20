using System.Text.Json.Nodes;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Notes.Core;
using Notes.Core.Capture;
using Notes.Core.Contexts;
using Notes.Core.Data;
using Notes.Core.Modules;
using Notes.Core.Sync;
using Notes.Modules.Stickies;
using Notes.Server;

namespace Notes.Server.Tests;

/// <summary>Core + modules on an in-memory SQLite database, with a controllable clock.</summary>
public sealed class TestApp : IAsyncDisposable
{
    private readonly SqliteConnection _connection;
    private readonly ServiceProvider _services;

    private TestApp(SqliteConnection connection, ServiceProvider services)
    {
        _connection = connection;
        _services = services;
    }

    public ManualTimeProvider Time => (ManualTimeProvider)_services.GetRequiredService<TimeProvider>();
    public SyncService Sync => _services.GetRequiredService<SyncService>();
    public EntityActions Actions => _services.GetRequiredService<EntityActions>();
    public ContextService Contexts => _services.GetRequiredService<ContextService>();
    public StickyService Stickies => _services.GetRequiredService<StickyService>();
    public CaptureTargetRegistry Targets => _services.GetRequiredService<CaptureTargetRegistry>();
    public IDbContextFactory<NotesDbContext> Db => _services.GetRequiredService<IDbContextFactory<NotesDbContext>>();

    /// <summary>Production composition (Post-its only), schema created via the real EF migrations.</summary>
    public static async Task<TestApp> CreateProductionAsync()
    {
        var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var services = new ServiceCollection()
            .AddSingleton<TimeProvider>(new ManualTimeProvider())
            .AddNotesCore(o => o.UseSqlite(connection, s => s.MigrationsAssembly(typeof(NotesComposition).Assembly.GetName().Name)), NotesComposition.Modules())
            .BuildServiceProvider();
        var app = new TestApp(connection, services);
        await using var db = await app.Db.CreateDbContextAsync();
        await db.Database.MigrateAsync();
        return app;
    }

    /// <summary>Post-its plus the test-only dummy module (proves the sync and conversion are generic).</summary>
    public static async Task<TestApp> CreateAsync()
    {
        var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        IModule[] modules = [new StickiesModule(), new DummyModule()];
        var services = new ServiceCollection()
            .AddSingleton<TimeProvider>(new ManualTimeProvider())
            .AddNotesCore(o => o.UseSqlite(connection), modules)
            .BuildServiceProvider();
        var app = new TestApp(connection, services);
        await using var db = await app.Db.CreateDbContextAsync();
        await db.Database.EnsureCreatedAsync();
        return app;
    }

    public async Task<PushResponse> PushAsync(params PushEnvelope[] entries) =>
        await Sync.PushAsync(entries, PushOrigin.Client);

    public async Task<PushEntryResult> PushOneAsync(PushEnvelope entry) => (await PushAsync(entry)).Results.Single();

    public async Task<IReadOnlyList<SyncEnvelope>> PullAllAsync(long since = 0)
    {
        var all = new List<SyncEnvelope>();
        while (true)
        {
            var page = await Sync.PullAsync(since, 500);
            Assert.False(page.FullResyncRequired);
            all.AddRange(page.Changes);
            since = page.Cursor;
            if (!page.HasMore) return all;
        }
    }

    public async Task<Sticky?> FindStickyAsync(Guid id)
    {
        await using var db = await Db.CreateDbContextAsync();
        return await db.Set<Sticky>().AsNoTracking().FirstOrDefaultAsync(s => s.Id == id);
    }

    public async Task<List<Sticky>> AllStickiesAsync()
    {
        await using var db = await Db.CreateDbContextAsync();
        return await db.Set<Sticky>().AsNoTracking().ToListAsync();
    }

    public async ValueTask DisposeAsync()
    {
        await _services.DisposeAsync();
        await _connection.DisposeAsync();
    }

    // --- envelope builders -------------------------------------------------------------------------------------

    public static PushEnvelope NewSticky(Guid id, DateTime modifiedAt, string content, string? title = null, Guid? contextId = null) =>
        new(Sticky.TypeName, id, null, modifiedAt, false, new JsonObject
        {
            ["contextId"] = (contextId ?? Context.DefaultId).ToString(),
            ["title"] = title,
            ["content"] = content,
            ["color"] = "yellow",
            ["pinned"] = false,
            ["position"] = 0,
        });

    public static PushEnvelope Edit(string type, Guid id, long? baseVersion, DateTime modifiedAt, JsonObject data) =>
        new(type, id, baseVersion, modifiedAt, false, data);

    public static PushEnvelope Delete(string type, Guid id, long? baseVersion, DateTime modifiedAt) =>
        new(type, id, baseVersion, modifiedAt, true, null);
}

public sealed class ManualTimeProvider : TimeProvider
{
    public DateTimeOffset Now { get; set; } = new(2026, 11, 20, 12, 0, 0, TimeSpan.Zero);
    public override DateTimeOffset GetUtcNow() => Now;
    public DateTime UtcNow => Now.UtcDateTime;
    public void Advance(TimeSpan by) => Now += by;
}
