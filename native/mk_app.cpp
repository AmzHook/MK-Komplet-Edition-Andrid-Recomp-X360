#include "mk_komplete_init.h"
#include "mk_app.h"

#include <rex/logging.h>

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
  REXLOG_INFO("MK experimental Android runtime: gameplay has not been validated");
}

REX_DEFINE_APP(mk_komplete, MkAndroidApp::Create)
