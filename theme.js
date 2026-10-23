/* Appearance preference; missing preference intentionally follows the device. */
(function () {
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  const theme = window.XXJ_THEME = {
    getMode() {
      const db = window.XXJ_DB;
      const mode = db?.getKv(db.K.THEME_MODE);
      return ["light", "dark", "system"].includes(mode) ? mode : "system";
    },
    isDark() {
      const mode = theme.getMode();
      return mode === "dark" || (mode === "system" && media.matches);
    },
    paint(dark = theme.isDark()) {
      document.body?.classList.toggle("dark", dark);
      document.documentElement.style.colorScheme = dark ? "dark" : "light";
      document.querySelector('meta[name="theme-color"]')?.setAttribute("content", dark ? "#101113" : "#f0f1f3");
      const select = document.getElementById("settings-theme-mode");
      if (select) select.value = theme.getMode();
    },
    setMode(mode) {
      if (!["light", "dark", "system"].includes(mode)) return;
      const db = window.XXJ_DB;
      if (!db?.isReady) return;
      db.setKv(db.K.THEME_MODE, mode);
      refresh();
    },
    onChange: null
  };
  function refresh() {
    theme.paint();
    theme.onChange?.();
  }
  media.addEventListener("change", () => {
    if (theme.getMode() === "system") refresh();
  });
  document.addEventListener("DOMContentLoaded", refresh);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) refresh();
  });
})();
