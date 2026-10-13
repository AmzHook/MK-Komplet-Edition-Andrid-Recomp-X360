#include "mk_komplete_init.h"
#include "mk_app.h"

#include <rex/logging.h>
#include <rex/filesystem/vfs.h>

#include <filesystem>
#include <system_error>

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
  REXLOG_INFO("MK experimental Android runtime 0.1.2: gameplay has not been validated");
}

void MkAndroidApp::OnPostLoadXexImage() {
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

REX_DEFINE_APP(mk_komplete, MkAndroidApp::Create)
