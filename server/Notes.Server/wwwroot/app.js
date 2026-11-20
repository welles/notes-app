window.notes = {
  setCookie(name, value) {
    document.cookie = `${name}=${encodeURIComponent(value)}; path=/; max-age=31536000; SameSite=Lax`;
  },
  attachCapture(inputId, dotNetRef) {
    const input = document.getElementById(inputId);
    if (!input) return;
    input._notesRef = dotNetRef;
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); window.notes.submitCapture(inputId); }
      else if (e.key === "Escape") { input.value = ""; input.blur(); }
    });
  },
  submitCapture(inputId) {
    const input = document.getElementById(inputId);
    if (!input || !input._notesRef) return;
    const text = input.value;
    input.value = "";
    if (text.trim()) input._notesRef.invokeMethodAsync("CaptureAsync", text);
  },
  init() {
    if (window.notes._initialized) return;
    window.notes._initialized = true;
    // "N" focuses quick capture (unless typing somewhere already).
    document.addEventListener("keydown", (e) => {
      if (e.key !== "n" && e.key !== "N") return;
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const t = e.target;
      if (t && (t.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(t.tagName))) return;
      const input = document.getElementById("notes-capture-input");
      if (input) { e.preventDefault(); input.focus(); }
    });
    // Ctrl+Enter in the post-it dialog clicks "Speichern".
    document.addEventListener("keydown", (e) => {
      if (e.key !== "Enter" || !(e.ctrlKey || e.metaKey)) return;
      const dialog = document.querySelector(".notes-sticky-dialog");
      const save = dialog && [...dialog.querySelectorAll("button")].find(b => b.textContent.trim() === "Speichern");
      if (save) { e.preventDefault(); save.click(); }
    });
  },
};
