cask "hue-manager" do
  version "0.0.0-9b0d881"
  sha256 "4f8353bf746c236262589424f10f990502c0c0fd352d60eb3f353180e2fb85a5"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/353337614",
      header: [
        "Authorization: token #{ENV.fetch("HOMEBREW_GITHUB_API_TOKEN", "")}",
        "Accept: application/octet-stream",
      ]
  name "Hue Manager"
  desc "Philips Hue lamp management desktop application"
  homepage "https://github.com/CommanderTvis/hue-manager"

  depends_on macos: ">= :ventura"

  app "Hue Manager.app"

  zap trash: [
    "~/Library/Preferences/io.github.commandertvis.huemanager.plist",
    "~/Library/Application Support/io.github.commandertvis.huemanager",
  ]
end
