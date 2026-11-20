using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.HttpOverrides;
using MudBlazor.Services;
using Notes.Core.Modules;
using Notes.Server.Components;
using Microsoft.EntityFrameworkCore;
using Notes.Core.Data;
using Notes.Server;
using Notes.Server.Api;
using Notes.Server.Security;

// Container healthcheck without curl in the runtime image: `Notes.Server --healthcheck`.
if (args.Contains("--healthcheck")) return await Healthcheck.RunAsync();
// Online backup of the SQLite database: `Notes.Server --backup /data/backup.db` (VACUUM INTO).
if (args is ["--backup", var backupPath]) return await Backup.RunAsync(backupPath);

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddNotes();
builder.Services.AddHostedService<TombstonePurgeService>();
builder.Services.AddCloudflareAccess(builder.Configuration);
builder.Services.AddNotesOpenApi();
builder.Services.ConfigureHttpJsonOptions(o => o.SerializerOptions.NumberHandling = System.Text.Json.Serialization.JsonNumberHandling.Strict);
builder.Services.AddRazorComponents().AddInteractiveServerComponents();
builder.Services.AddMudServices();
// Persist keys so a container restart does not break open browser tabs (antiforgery, circuits).
builder.Services.AddDataProtection()
    .SetApplicationName("Notes")
    .PersistKeysToFileSystem(new DirectoryInfo(builder.Configuration["Notes:DataProtectionKeysPath"] ?? "/data/keys"));
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

app.UseAntiforgery();
app.MapStaticAssets();

app.MapOpenApi("/api/openapi.json");
app.MapMetaEndpoints();
app.MapSyncEndpoints();
app.MapExportEndpoints();
app.MapRazorComponents<App>()
    .AddInteractiveServerRenderMode()
    .AddAdditionalAssemblies(app.Services.GetRequiredService<ModuleSet>().Modules
        .Select(m => m.GetType().Assembly).Where(a => a != typeof(Program).Assembly).Distinct().ToArray());

app.Run();
return 0;

public partial class Program;
