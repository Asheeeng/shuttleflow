package edu.gzu.shuttle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("demo & !prod & !production")
@RequestMapping("/api/admin/demo")
class DemoController {
  final DemoService demo;

  DemoController(DemoService d) {
    demo = d;
  }

  record Reset(@NotBlank String confirmation) {}

  @PostMapping("/peak")
  Api<?> peak(@Valid @RequestBody Reset body) {
    return Api.ok(demo.peak(body.confirmation()));
  }

  @PostMapping("/reset")
  Api<?> reset(@Valid @RequestBody Reset body) {
    demo.reset(body.confirmation());
    return Api.ok(Api.map("message", "演示数据已重置，车辆在A站，服务器时间已刷新"));
  }
}
