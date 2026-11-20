using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Notes.Server.Data.Migrations
{
    /// <inheritdoc />
    public partial class Initial : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "core_context",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    Name = table.Column<string>(type: "TEXT", nullable: false),
                    Color = table.Column<string>(type: "TEXT", nullable: true),
                    Position = table.Column<double>(type: "REAL", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    ModifiedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    DeletedAt = table.Column<DateTime>(type: "TEXT", nullable: true),
                    Version = table.Column<long>(type: "INTEGER", nullable: false),
                    ExtraJson = table.Column<string>(type: "TEXT", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_core_context", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "stickies_sticky",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    Title = table.Column<string>(type: "TEXT", nullable: true),
                    Content = table.Column<string>(type: "TEXT", nullable: false),
                    Color = table.Column<string>(type: "TEXT", nullable: false),
                    Pinned = table.Column<bool>(type: "INTEGER", nullable: false),
                    Position = table.Column<double>(type: "REAL", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    ModifiedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    DeletedAt = table.Column<DateTime>(type: "TEXT", nullable: true),
                    Version = table.Column<long>(type: "INTEGER", nullable: false),
                    ExtraJson = table.Column<string>(type: "TEXT", nullable: true),
                    ContextId = table.Column<Guid>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_stickies_sticky", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "sync_changes",
                columns: table => new
                {
                    EntityType = table.Column<string>(type: "TEXT", nullable: false),
                    EntityId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Version = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_sync_changes", x => new { x.EntityType, x.EntityId });
                });

            migrationBuilder.CreateTable(
                name: "sync_push_log",
                columns: table => new
                {
                    EntityType = table.Column<string>(type: "TEXT", nullable: false),
                    EntityId = table.Column<Guid>(type: "TEXT", nullable: false),
                    ModifiedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    Status = table.Column<string>(type: "TEXT", nullable: false),
                    LoggedAt = table.Column<DateTime>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_sync_push_log", x => new { x.EntityType, x.EntityId, x.ModifiedAt });
                });

            migrationBuilder.CreateTable(
                name: "sync_state",
                columns: table => new
                {
                    Id = table.Column<int>(type: "INTEGER", nullable: false),
                    LastVersion = table.Column<long>(type: "INTEGER", nullable: false),
                    PurgedThroughVersion = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_sync_state", x => x.Id);
                });

            migrationBuilder.InsertData(
                table: "core_context",
                columns: new[] { "Id", "Color", "CreatedAt", "DeletedAt", "ExtraJson", "ModifiedAt", "Name", "Position", "Version" },
                values: new object[] { new Guid("019a0000-0000-7000-8000-000000000001"), null, new DateTime(2026, 1, 1, 0, 0, 0, 0, DateTimeKind.Utc), null, null, new DateTime(2026, 1, 1, 0, 0, 0, 0, DateTimeKind.Utc), "Privat", 0.0, 1L });

            migrationBuilder.InsertData(
                table: "sync_changes",
                columns: new[] { "EntityId", "EntityType", "Version" },
                values: new object[] { new Guid("019a0000-0000-7000-8000-000000000001"), "core.context", 1L });

            migrationBuilder.InsertData(
                table: "sync_state",
                columns: new[] { "Id", "LastVersion", "PurgedThroughVersion" },
                values: new object[] { 1, 1L, 0L });

            migrationBuilder.CreateIndex(
                name: "IX_core_context_Version",
                table: "core_context",
                column: "Version");

            migrationBuilder.CreateIndex(
                name: "IX_stickies_sticky_ContextId_DeletedAt",
                table: "stickies_sticky",
                columns: new[] { "ContextId", "DeletedAt" });

            migrationBuilder.CreateIndex(
                name: "IX_stickies_sticky_Version",
                table: "stickies_sticky",
                column: "Version");

            migrationBuilder.CreateIndex(
                name: "IX_sync_changes_Version",
                table: "sync_changes",
                column: "Version",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_sync_push_log_LoggedAt",
                table: "sync_push_log",
                column: "LoggedAt");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "core_context");

            migrationBuilder.DropTable(
                name: "stickies_sticky");

            migrationBuilder.DropTable(
                name: "sync_changes");

            migrationBuilder.DropTable(
                name: "sync_push_log");

            migrationBuilder.DropTable(
                name: "sync_state");
        }
    }
}
