using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;
using Notes.Core.Contexts;
using Notes.Core.Modules;
using Notes.Core.Sync;

namespace Notes.Core.Data;

/// <summary>
/// Shared DbContext. Core owns the sync tables and contexts; each module adds its own tables via <see cref="IModule.ConfigureModel"/>.
/// Every save that touches a <see cref="SyncEntity"/> assigns new versions and upserts <c>sync_changes</c> in the same transaction.
/// </summary>
public class NotesDbContext(DbContextOptions<NotesDbContext> options, ModuleSet modules) : DbContext(options)
{
    private readonly List<EntityChange> _savedChanges = [];

    public ModuleSet Modules { get; } = modules;

    public DbSet<SyncChange> SyncChanges => Set<SyncChange>();
    public DbSet<SyncState> SyncState => Set<SyncState>();
    public DbSet<SyncPushLogEntry> SyncPushLog => Set<SyncPushLogEntry>();
    public DbSet<Context> Contexts => Set<Context>();

    /// <summary>Changes written by SaveChanges since the last call to <see cref="TakeSavedChanges"/>; published after commit.</summary>
    public IReadOnlyList<EntityChange> TakeSavedChanges()
    {
        var list = _savedChanges.ToList();
        _savedChanges.Clear();
        return list;
    }

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<SyncChange>(e =>
        {
            e.ToTable("sync_changes");
            e.HasKey(x => new { x.EntityType, x.EntityId });
            e.HasIndex(x => x.Version).IsUnique();
        });
        b.Entity<SyncState>(e =>
        {
            e.ToTable("sync_state");
            e.Property(x => x.Id).ValueGeneratedNever();
            e.HasData(new SyncState { Id = 1, LastVersion = 1, PurgedThroughVersion = 0 });
        });
        b.Entity<SyncPushLogEntry>(e =>
        {
            e.ToTable("sync_push_log");
            e.HasKey(x => new { x.EntityType, x.EntityId, x.ModifiedAt });
            e.Property(x => x.Status).HasConversion<string>();
            e.HasIndex(x => x.LoggedAt);
        });
        b.ApplyConfiguration(new ContextConfiguration());
        b.Entity<SyncChange>().HasData(new SyncChange { EntityType = Context.TypeName, EntityId = Context.DefaultId, Version = 1 });

        foreach (var module in Modules.Modules) module.ConfigureModel(b);

        foreach (var type in b.Model.GetEntityTypes().Where(t => typeof(SyncEntity).IsAssignableFrom(t.ClrType)))
        {
            b.Entity(type.ClrType).HasKey(nameof(SyncEntity.Id));
            b.Entity(type.ClrType).HasIndex(nameof(SyncEntity.Version));
            b.Entity(type.ClrType).Ignore(nameof(SyncEntity.IsDeleted));
        }
    }

    protected override void ConfigureConventions(ModelConfigurationBuilder c)
    {
        c.Properties<DateTime>().HaveConversion<UtcDateTimeConverter>();
    }

    public override int SaveChanges(bool acceptAllChangesOnSuccess) =>
        throw new NotSupportedException("Use SaveChangesAsync.");

    public override async Task<int> SaveChangesAsync(bool acceptAllChangesOnSuccess, CancellationToken ct = default)
    {
        ChangeTracker.DetectChanges();
        var changed = ChangeTracker.Entries<SyncEntity>()
            .Where(e => e.State is EntityState.Added or EntityState.Modified)
            .ToList();
        if (changed.Count == 0) return await base.SaveChangesAsync(acceptAllChangesOnSuccess, ct);

        var ownTransaction = Database.CurrentTransaction is null ? await Database.BeginTransactionAsync(ct) : null;
        try
        {
            var version = await AllocateVersionsAsync(changed.Count, ct) - changed.Count + 1;
            var newChanges = new List<EntityChange>();
            foreach (var entry in changed)
            {
                var entity = entry.Entity;
                var entityType = SyncEntityTypeAttribute.Of(entity.GetType());
                entity.Version = version++;
                var row = await SyncChanges.FindAsync([entityType, entity.Id], ct);
                if (row is null) SyncChanges.Add(new SyncChange { EntityType = entityType, EntityId = entity.Id, Version = entity.Version });
                else row.Version = entity.Version;
                newChanges.Add(new EntityChange(new EntityRef(entityType, entity.Id), entity.Version, entity.IsDeleted));
            }

            var result = await base.SaveChangesAsync(acceptAllChangesOnSuccess, ct);
            if (ownTransaction is not null) await ownTransaction.CommitAsync(ct);
            _savedChanges.AddRange(newChanges);
            return result;
        }
        finally
        {
            if (ownTransaction is not null) await ownTransaction.DisposeAsync();
        }
    }

    /// <summary>Reserves <paramref name="count"/> versions from the global sequence; returns the last one.</summary>
    private async Task<long> AllocateVersionsAsync(int count, CancellationToken ct)
    {
        var connection = Database.GetDbConnection();
        await using var cmd = connection.CreateCommand();
        cmd.Transaction = Database.CurrentTransaction!.GetDbTransaction();
        cmd.CommandText = "UPDATE sync_state SET LastVersion = LastVersion + $count WHERE Id = 1 RETURNING LastVersion";
        var p = cmd.CreateParameter();
        p.ParameterName = "$count";
        p.Value = count;
        cmd.Parameters.Add(p);
        return Convert.ToInt64(await cmd.ExecuteScalarAsync(ct));
    }
}

public sealed record EntityChange(EntityRef Ref, long Version, bool Deleted);

/// <summary>Stores DateTime as UTC and reads it back with <see cref="DateTimeKind.Utc"/>.</summary>
public sealed class UtcDateTimeConverter() : ValueConverter<DateTime, DateTime>(
    v => SyncTime.Normalize(v),
    v => DateTime.SpecifyKind(v, DateTimeKind.Utc));

/// <summary>Builds a separate EF model per module set (tests compose extra modules).</summary>
public sealed class ModuleModelCacheKeyFactory : IModelCacheKeyFactory
{
    public object Create(DbContext context, bool designTime) =>
        context is NotesDbContext n ? (context.GetType(), n.Modules.ModelKey, designTime) : (context.GetType(), designTime);
}
