cask "hue-manager" do
  version "2026.06.23-bb78c68"
  sha256 "8c5eb4f2f163850a7cdbaaab36114ddbd54811aae82c8b1f60e4a74dc8b75048"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455629275",
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
