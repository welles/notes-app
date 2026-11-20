using Microsoft.EntityFrameworkCore;
using Notes.Core;
using Notes.Core.Data;
using Notes.Server;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddNotes(builder.Configuration.GetConnectionString("Default") ?? NotesComposition.DefaultConnectionString);
builder.Services.AddHostedService<TombstonePurgeService>();

var app = builder.Build();

await using (var scope = app.Services.CreateAsyncScope())
{
    var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<NotesDbContext>>();
    await using var db = await factory.CreateDbContextAsync();
    await db.Database.MigrateAsync();
}

app.MapGet("/healthz", () => Results.Ok("ok"));

app.Run();

public partial class Program;
