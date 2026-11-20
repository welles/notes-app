namespace Notes.Server;

/// <summary>Used by the Docker HEALTHCHECK: GET /healthz on the local port, exit code 0/1.</summary>
internal static class Healthcheck
{
    public static async Task<int> RunAsync()
    {
        var port = Environment.GetEnvironmentVariable("ASPNETCORE_HTTP_PORTS")?.Split(';', ',')[0] ?? "8080";
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(3) };
            var response = await http.GetAsync($"http://127.0.0.1:{port}/healthz");
            return response.IsSuccessStatusCode ? 0 : 1;
        }
        catch
        {
            return 1;
        }
    }
}
