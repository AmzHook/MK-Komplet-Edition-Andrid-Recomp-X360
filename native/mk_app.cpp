#include "mk_komplete_init.h"
#include "mk_app.h"
#include "mk_file_trace.h"

#include <rex/logging.h>
#include <rex/filesystem/vfs.h>
#include <rex/kernel/xboxkrnl/io.h>
#include <SDL3/SDL_events.h>

#include <filesystem>
#include <system_error>

namespace {
mk::diagnostics::FileTraceBudget file_trace_budget;

void TraceGuestFileAttempt(std::string_view path) noexcept {
  // Keep diagnostics independent of guest I/O. The callback and state live for
  // the process lifetime, including callbacks already loaded during shutdown.
  try {
    const auto decision = file_trace_budget.Observe(path);
    if (decision.log_path) {
      REXLOG_INFO("MK file request: kind={} path={:?}{}",
                  decision.movie ? "movie" : "startup", path.substr(0, 512),
                  path.size() > 512 ? " [truncated]" : "");
    }
    if (decision.startup_limit) {
      REXLOG_INFO("MK file trace: startup limit reached (128); tracing up to 64 additional movie requests");
    }
    if (decision.movie_limit) {
      REXLOG_INFO("MK file trace: additional movie limit reached (64)");
    }
  } catch (...) {
    // A logging failure must not change the game's file-open result.
  }
}
}  // namespace

MkAndroidApp::MkAndroidApp(rex::ui::WindowedAppContext& context)
    : rex::ReXApp(context, "mk_komplete", PPCImageConfig) {}

std::unique_ptr<rex::ui::WindowedApp> MkAndroidApp::Create(
    rex::ui::WindowedAppContext& context) {
  return std::unique_ptr<MkAndroidApp>(new MkAndroidApp(context));
}

void MkAndroidApp::OnPreSetup(rex::RuntimeConfig& config) {
  config.gpu_plugin = "xenos";
}

void MkAndroidApp::OnLoadXexImage(std::string& image) {
  // ReXApp mounts the --game_data_root directory as game:. The XEX and all
  // other assets remain in the private directory populated by the importer.
  image = "game:\\default.xex";
}

void MkAndroidApp::OnPostInitLogging() {
  REXLOG_INFO("MK experimental Android runtime 0.1.10: gameplay has not been validated");
  REXLOG_INFO("MK Android idle waits: SDL poll sentinel enabled={}",
              SDL_EventEnabled(SDL_EVENT_POLL_SENTINEL));
}

void MkAndroidApp::OnPostLoadXexImage() {
  rex::kernel::xboxkrnl::SetGuestFileOpenObserver(TraceGuestFileAttempt);
  // Read-only diagnostics: use the same guest VFS as MK, including its
  // case-insensitive host fallback. Missing optional config is not fatal here.
  auto* file_system = runtime()->file_system();
  const bool game_config = file_system->ResolvePath("game:\\Config\\Xe-MK9Engine.ini") != nullptr;
  const bool drive_config = file_system->ResolvePath("D:\\Config\\Xe-MK9Engine.ini") != nullptr;
  REXLOG_INFO("MK asset audit: game root={}", game_data_root().string());
  REXLOG_INFO("MK asset audit: Config/Xe-MK9Engine.ini game:={} D:={}",
              game_config ? "available" : "not found", drive_config ? "available" : "not found");
  std::error_code error;
  std::string names;
  size_t count = 0;
  for (std::filesystem::directory_iterator entry(game_data_root(), error), end;
       !error && entry != end && count < 32; entry.increment(error), ++count) {
    if (!names.empty()) names += ", ";
    names += entry->path().filename().string();
  }
  REXLOG_INFO("MK asset audit: root entries (first 32)={}", names);
  if (error) REXLOG_WARN("MK asset audit: directory listing failed: {}", error.message());
}

void MkAndroidApp::OnShutdown() {
  rex::kernel::xboxkrnl::SetGuestFileOpenObserver(nullptr);
}

REX_DEFINE_APP(mk_komplete, MkAndroidApp::Create)
