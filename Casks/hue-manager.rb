cask "hue-manager" do
  version "0.0.0-69ecd69"
  sha256 "4191e2fd4c542cd7e791269e5524b2da796ec190d648abf14dab22dc64caf4b7"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/ASSET_PLACEHOLDER",
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
