cask "hue-manager" do
  version "2026.06.27-4ecb0f9"
  sha256 "cb0192c82fd7c0876ca28e603ddbc42d9aa1614c8845786b6bcd3b09f8beabd4"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/459533800",
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
