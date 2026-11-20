using Microsoft.Data.Sqlite;

namespace Notes.Server;

/// <summary>Consistent online backup of the database while the server keeps running (<c>VACUUM INTO</c>).</summary>
internal static class Backup
{
    public static async Task<int> RunAsync(string target)
    {
        var connectionString = Environment.GetEnvironmentVariable("ConnectionStrings__Default") ?? NotesComposition.DefaultConnectionString;
        if (File.Exists(target))
        {
            Console.Error.WriteLine($"{target} already exists.");
            return 1;
        }
        await using var connection = new SqliteConnection(connectionString);
        await connection.OpenAsync();
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = "VACUUM INTO $target";
        cmd.Parameters.AddWithValue("$target", Path.GetFullPath(target));
        await cmd.ExecuteNonQueryAsync();
        Console.WriteLine($"Backup written to {Path.GetFullPath(target)}");
        return 0;
    }
}
