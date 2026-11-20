using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.EntityFrameworkCore;
using Notes.Core.Data;
using Notes.Server;
using Notes.Server.Api;
using Notes.Server.Security;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddNotes();
builder.Services.AddHostedService<TombstonePurgeService>();
builder.Services.AddCloudflareAccess(builder.Configuration);
builder.Services.AddNotesOpenApi();
builder.Services.ConfigureHttpJsonOptions(o => o.SerializerOptions.NumberHandling = System.Text.Json.Serialization.JsonNumberHandling.Strict);
builder.Services.Configure<ForwardedHeadersOptions>(o =>
{
    // Only reachable through cloudflared in the same Docker network.
    o.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto | ForwardedHeaders.XForwardedHost;
    o.KnownIPNetworks.Clear();
    o.KnownProxies.Clear();
});

var app = builder.Build();

// Build-time OpenAPI generation (GetDocument.Insider) runs this file without real configuration or database.
var generatingOpenApi = System.Reflection.Assembly.GetEntryAssembly()?.GetName().Name == "GetDocument.Insider";
if (!generatingOpenApi)
{
    app.ValidateCloudflareAccess();
    await using var scope = app.Services.CreateAsyncScope();
    var factory = scope.ServiceProvider.GetRequiredService<IDbContextFactory<NotesDbContext>>();
    await using var db = await factory.CreateDbContextAsync();
    await db.Database.MigrateAsync();
}

app.UseForwardedHeaders();
app.MapGet("/healthz", () => Results.Ok("ok")).ExcludeFromDescription();
app.UseMiddleware<CloudflareAccessMiddleware>();
app.UseMiddleware<ProtocolMiddleware>();

app.MapOpenApi("/api/openapi.json");
app.MapMetaEndpoints();
app.MapSyncEndpoints();

app.Run();

public partial class Program;
