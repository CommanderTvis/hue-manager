cask "hue-manager" do
  version "2026.06.23-ddd17ac"
  sha256 "084bb7d2f36add8d267029420af0260a1d76c9b000731a85c67cf5bb432cbbe6"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455754238",
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
