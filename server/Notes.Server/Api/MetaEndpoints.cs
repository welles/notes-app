using System.Reflection;
using Notes.Core.Capture;
using Notes.Core.Modules;
using Notes.Core.Sync;

namespace Notes.Server.Api;

public sealed record ProtocolRange(int Min, int Max);

public sealed record MetaResponse(
    string ServerVersion,
    ProtocolRange Protocol,
    IReadOnlyList<string> EntityTypes,
    IReadOnlyList<string> Capabilities,
    IReadOnlyDictionary<string, string> MinClientVersion);

public static class MetaEndpoints
{
    public static readonly ProtocolRange Protocol = new(1, 1);

    public static string ServerVersion { get; } =
        typeof(MetaEndpoints).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0] ?? "0.0.0";

    public static void MapMetaEndpoints(this IEndpointRouteBuilder app) =>
        app.MapGet("/api/meta", (SyncHandlerRegistry handlers, ModuleSet modules, CaptureTargetRegistry targets, IConfiguration config) =>
                new MetaResponse(
                    ServerVersion,
                    Protocol,
                    handlers.EntityTypes.ToList(),
                    ["sync.v1", "contexts", .. modules.Modules.Select(m => m.Key), .. targets.ConversionAvailable ? ["convert"] : Array.Empty<string>()],
                    config.GetSection("Notes:MinClientVersion").GetChildren()
                        .Where(c => !string.IsNullOrWhiteSpace(c.Value))
                        .ToDictionary(c => c.Key, c => c.Value!)))
            .WithTags("Meta")
            .WithName("GetMeta")
            .WithSummary("Server version, supported protocol versions, known entity types and capabilities.");
}

/// <summary>
/// Clients send <c>Notes-Client: android/1.4.0</c> and <c>Notes-Protocol: 1</c>. Only an unsupported protocol is refused (426);
/// requests without the header (browser, curl) are allowed.
/// </summary>
public sealed class ProtocolMiddleware(RequestDelegate next, ILogger<ProtocolMiddleware> log)
{
    public const string ProtocolHeader = "Notes-Protocol";
    public const string ClientHeader = "Notes-Client";

    public async Task InvokeAsync(HttpContext http)
    {
        if (http.Request.Path.StartsWithSegments("/api") && http.Request.Headers.TryGetValue(ProtocolHeader, out var value))
        {
            if (!int.TryParse(value, out var protocol) || protocol < MetaEndpoints.Protocol.Min || protocol > MetaEndpoints.Protocol.Max)
            {
                log.LogInformation("Refusing client {Client} with protocol {Protocol}", http.Request.Headers[ClientHeader].ToString(), value.ToString());
                http.Response.StatusCode = StatusCodes.Status426UpgradeRequired;
                await http.Response.WriteAsJsonAsync(new { error = "unsupported_protocol", protocol = MetaEndpoints.Protocol });
                return;
            }
        }
        await next(http);
    }
}
