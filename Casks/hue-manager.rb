cask "hue-manager" do
  version "2026.06.23-95833d3"
  sha256 "b5f3a9c61db9644fe336fdc44e84b32e8a294359434651ca0ee72b56654dbf09"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455774317",
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
