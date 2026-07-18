cask "hue-manager" do
  version "2026.07.18-9e6881a"
  sha256 "0e2ce72abc49e8b0940e2c1ace4178a3698b9bf0d31cd4a5b653b8d0de64d9dd"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/481361293",
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
