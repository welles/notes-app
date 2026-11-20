using Microsoft.EntityFrameworkCore;

namespace Notes.Server.Tests;

public sealed class MigrationTests
{
    [Fact]
    public async Task Migrations_match_the_model()
    {
        await using var app = await TestApp.CreateProductionAsync();
        await using var db = await app.Db.CreateDbContextAsync();

        Assert.Empty(await db.Database.GetPendingMigrationsAsync());
        Assert.False(db.Database.HasPendingModelChanges(), "Model changed without a migration: run dotnet ef migrations add.");
    }
}
