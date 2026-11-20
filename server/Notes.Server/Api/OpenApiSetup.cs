using Microsoft.OpenApi;
using Notes.Core.Contexts;
using Notes.Core.Sync;
using Notes.Modules.Stickies;

namespace Notes.Server.Api;

public static class OpenApiSetup
{
    /// <summary>
    /// The <c>data</c> of envelopes is free-form JSON (unknown fields must survive). Its per-type shape is documented as
    /// extra component schemas named after the entity type's data record (e.g. <c>StickyData</c>).
    /// </summary>
    public static IServiceCollection AddNotesOpenApi(this IServiceCollection services) =>
        services.AddOpenApi(o =>
        {
            o.AddDocumentTransformer((doc, _, _) =>
            {
                doc.Info = new OpenApiInfo
                {
                    Title = "Notes API",
                    Version = "1",
                    Description = "Generic sync protocol of Notes. Rules: changes are additive only; unknown fields and enum values must be preserved.",
                };
                return Task.CompletedTask;
            });
            o.AddDocumentTransformer(async (doc, ctx, ct) =>
            {
                // Registry lookup is not possible at build-time generation without the full host, so list the data records explicitly.
                foreach (var (entityType, type) in DataSchemas)
                {
                    var schema = await ctx.GetOrCreateSchemaAsync(type, null, ct);
                    schema.Description = $"data of entity type {entityType}";
                    doc.AddComponent(type.Name, schema);
                }
            });
        });

    public static readonly IReadOnlyList<(string EntityType, Type Type)> DataSchemas =
    [
        (Context.TypeName, typeof(ContextData)),
        (Sticky.TypeName, typeof(StickyData)),
    ];
}
