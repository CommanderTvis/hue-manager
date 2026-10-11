/* Fixed-purpose health probe for the runtime image without curl. */
#include <arpa/inet.h>
#include <signal.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

int main(void) {
    /* Bound the entire probe, including connect and partial responses. */
    alarm(5);
    signal(SIGPIPE, SIG_IGN);

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return 1;

    struct sockaddr_in address = {
        .sin_family = AF_INET,
        .sin_port = htons(8080),
        .sin_addr.s_addr = htonl(INADDR_LOOPBACK),
    };
    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) != 0) {
        close(fd);
        return 1;
    }

    const char request[] = "GET /api/status HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n";
    size_t sent = 0;
    while (sent < sizeof(request) - 1) {
        ssize_t count = send(fd, request + sent, sizeof(request) - 1 - sent, 0);
        if (count <= 0) {
            close(fd);
            return 1;
        }
        sent += (size_t)count;
    }

    char response[12];
    size_t received = 0;
    while (received < sizeof(response)) {
        ssize_t count = recv(fd, response + received, sizeof(response) - received, 0);
        if (count <= 0) {
            close(fd);
            return 1;
        }
        received += (size_t)count;
    }
    close(fd);

    return !((memcmp(response, "HTTP/1.1 200 ", sizeof(response)) == 0) ||
             (memcmp(response, "HTTP/1.0 200 ", sizeof(response)) == 0));
}
