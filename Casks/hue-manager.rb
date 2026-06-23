cask "hue-manager" do
  version "2026.06.23-fdf6d26"
  sha256 "9d044f112cca626acd2ccb2122ac464c0ee6b0447df3e560bf55ced7f162d129"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455813385",
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
