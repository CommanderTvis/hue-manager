cask "hue-manager" do
  version "2026.06.23-f7158b9"
  sha256 "62f8168ab9b6dff039e580a7f610c9ba9cf94ac1bf706c3f50c5b63eae66c9e6"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455741449",
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
