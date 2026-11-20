using System.Net.Http.Json;
using System.Text.Json.Nodes;

namespace Notes.Server.Tests;

/// <summary>Every published fixture must round-trip: unknown fields and enum values survive the server.</summary>
public sealed class ContractFixtureTests
{
    public static TheoryData<string> Fixtures()
    {
        var data = new TheoryData<string>();
        foreach (var f in Directory.GetFiles(Path.Combine(AppContext.BaseDirectory, "fixtures"), "*.json", SearchOption.AllDirectories).Order())
            data.Add(Path.GetRelativePath(Path.Combine(AppContext.BaseDirectory, "fixtures"), f));
        return data;
    }

    [Theory]
    [MemberData(nameof(Fixtures))]
    public async Task Fixture_round_trips(string fixture)
    {
        var doc = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "fixtures", fixture)))!.AsObject();
        var envelope = doc["envelope"]!.AsObject();
        var data = envelope["data"]!.AsObject();
        await using var factory = new NotesWebFactory();
        var http = factory.CreateClient();

        var push = new JsonObject
        {
            ["changes"] = new JsonArray(new JsonObject
            {
                ["entityType"] = envelope["entityType"]!.DeepClone(),
                ["id"] = envelope["id"]!.DeepClone(),
                ["baseVersion"] = null,
                ["modifiedAt"] = data["modifiedAt"]!.DeepClone(),
                ["deleted"] = envelope["deleted"]!.DeepClone(),
                ["data"] = data.DeepClone(),
            }),
        };
        var result = (await (await http.PostAsJsonAsync("/api/sync/push", push)).Content.ReadFromJsonAsync<JsonObject>())!["results"]![0]!;

        switch (doc["expect"]!.GetValue<string>())
        {
            case "rejected_unknown_type":
                Assert.Equal("rejected", result["status"]!.GetValue<string>());
                Assert.Equal("unknown_type", result["reason"]!.GetValue<string>());
                break;
            case "roundtrip":
                Assert.Equal("accepted", result["status"]!.GetValue<string>());
                var pulled = (await http.GetFromJsonAsync<JsonObject>("/api/sync/changes?since=1"))!["changes"]!.AsArray().Single()!;
                Assert.Equal(envelope["deleted"]!.GetValue<bool>(), pulled["deleted"]!.GetValue<bool>());
                var expected = (JsonObject)data.DeepClone();
                var actual = (JsonObject)pulled["data"]!.DeepClone();
                expected.Remove("version");
                actual.Remove("version");
                Assert.Equal(Normalize(expected), Normalize(actual));
                break;
            default:
                throw new InvalidOperationException("Unknown expectation.");
        }
    }

    private static string Normalize(JsonObject o) =>
        new JsonObject(o.OrderBy(p => p.Key).Select(p => KeyValuePair.Create(p.Key, p.Value?.DeepClone()))).ToJsonString();
}
