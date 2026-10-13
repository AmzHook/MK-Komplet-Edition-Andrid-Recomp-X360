#pragma once

#include <memory>
#include <string>

#include <rex/rex_app.h>

// Original, minimal application hooks. MK-specific corrections remain a
// separate step after the generated code and the runtime can be validated.
class MkAndroidApp final : public rex::ReXApp {
 public:
  static std::unique_ptr<rex::ui::WindowedApp> Create(
      rex::ui::WindowedAppContext& context);

 protected:
  void OnPreSetup(rex::RuntimeConfig& config) override;
  void OnLoadXexImage(std::string& image) override;
  void OnPostInitLogging() override;
  void OnPostLoadXexImage() override;

 private:
  explicit MkAndroidApp(rex::ui::WindowedAppContext& context);
};
